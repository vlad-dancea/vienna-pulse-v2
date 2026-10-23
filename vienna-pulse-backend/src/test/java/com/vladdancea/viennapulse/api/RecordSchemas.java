package com.vladdancea.viennapulse.api;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Tells springdoc what Jackson writes for the API's records, which it cannot see by itself: every
/// property, `null` only where the record component is `@Nullable`, the fixed values of GeoJSON
/// members such as `"type": "Point"`, and a record formatted as an array as a list of exactly its
/// components.
// Swagger's models use raw schema types throughout.
@SuppressWarnings({"rawtypes", "unchecked"})
class RecordSchemas implements ModelConverter {
    @Override
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        var raw = Json.mapper().constructType(type.getType()).getRawClass();
        var format = raw.getAnnotation(JsonFormat.class);
        if (raw.isRecord() && format != null && format.shape() == JsonFormat.Shape.ARRAY) {
            return tuple(raw, context);
        }
        if (!chain.hasNext()) {
            return null;
        }
        var schema = chain.next().resolve(type, context, chain);
        if (schema != null && raw.isRecord()) {
            // A record that is already defined comes back as a reference to its schema.
            var model = schema.get$ref() == null
                    ? schema
                    : context.getDefinedModels()
                            .get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
            if (model != null) {
                describeProperties(model, nullableNames(raw), constants(raw));
            }
        }
        return schema;
    }

    private static Schema tuple(Class<?> record, ModelConverterContext context) {
        var items = new ArrayList<Schema>();
        for (var component : record.getRecordComponents()) {
            items.add(context.resolve(new AnnotatedType(component.getGenericType())));
        }
        return new JsonSchema()
                .typesItem("array")
                .prefixItems(items)
                .minItems(items.size())
                .maxItems(items.size());
    }

    // Jackson writes nulls too, so every property is required. A sealed subtype keeps its own
    // properties in the second part of an allOf.
    private static void describeProperties(Schema model, Set<String> nullable, Map<String, Object> constants) {
        var parts = new ArrayList<Schema>(List.of(model));
        if (model.getAllOf() != null) {
            parts.addAll(model.getAllOf());
        }
        var missing = new HashSet<>(nullable);
        for (var part : parts) {
            var properties = (Map<String, Schema>) part.getProperties();
            if (properties == null) {
                continue;
            }
            properties.forEach((name, property) -> {
                if (part.getRequired() == null || !part.getRequired().contains(name)) {
                    part.addRequiredItem(name);
                }
                if (nullable.contains(name)) {
                    if (property.getTypes() == null) {
                        throw new IllegalStateException("Only a property with a type can be nullable: " + name);
                    }
                    property.addType("null");
                    missing.remove(name);
                }
                if (constants.containsKey(name)) {
                    property.setConst(constants.get(name));
                }
            });
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("springdoc wrote no property for " + missing);
        }
    }

    // GeoJSON's fixed members are methods that ignore the components, so an instance with empty
    // components has their values. Without them, an empty MultiLineString would also match Point.
    private static Map<String, Object> constants(Class<?> record) {
        var accessors = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getAccessor)
                .toList();
        var constants = new HashMap<String, Object>();
        for (var method : record.getMethods()) {
            var property = method.getAnnotation(JsonProperty.class);
            if (property != null
                    && method.getParameterCount() == 0
                    && method.getReturnType() == String.class
                    && !accessors.contains(method)) {
                var name = property.value().isEmpty() ? method.getName() : property.value();
                try {
                    constants.put(name, requireNonNull(method.invoke(empty(record))));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read " + record.getSimpleName() + "." + name, e);
                }
            }
        }
        return constants;
    }

    private static Object empty(Class<?> record) throws ReflectiveOperationException {
        var types = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        // An array of one element holds the type's default: null, zero or false.
        var values = Arrays.stream(types)
                .map(type -> Array.get(Array.newInstance(type, 1), 0))
                .toArray();
        return record.getDeclaredConstructor(types).newInstance(values);
    }

    // A component that Jackson unwraps brings its own nullable components along.
    private static Set<String> nullableNames(Class<?> record) {
        var names = new HashSet<String>();
        for (var component : record.getRecordComponents()) {
            if (component.getAccessor().isAnnotationPresent(JsonUnwrapped.class)) {
                names.addAll(nullableNames(component.getType()));
            } else if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                names.add(component.getName());
            }
        }
        return names;
    }
}
