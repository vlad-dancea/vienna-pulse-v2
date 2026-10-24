package com.vladdancea.viennapulse.api;

import static java.util.Objects.requireNonNull;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.service.GenericResponseService;

/// Tells springdoc what the responses [CachedResponses] keeps hold. The controllers return them as
/// bytes that Jackson already wrote, so springdoc would describe a string, but each holds what one
/// service method returns.
class CachedBodies implements OpenApiCustomizer {
    private static final Map<String, Type> BODIES = Map.of(
            "/api/lines", returnType(LineService.class, "lines", LocalDate.class),
            "/api/stops", returnType(StopService.class, "stops"),
            "/api/vehicles", returnType(VehicleService.class, "vehicles", Instant.class));

    private final GenericResponseService responses;

    CachedBodies(GenericResponseService responses) {
        this.responses = responses;
    }

    @Override
    public void customise(OpenAPI openApi) {
        var components = requireNonNull(openApi.getComponents());
        var missing = new HashSet<>(BODIES.keySet());
        requireNonNull(openApi.getPaths()).forEach((path, item) -> {
            for (var operation : item.readOperations()) {
                var ok = operation.getResponses().get("200");
                if (ok == null || ok.getContent() == null) {
                    continue;
                }
                var body = BODIES.get(path);
                if (body != null) {
                    var mediaTypes = ok.getContent().keySet().toArray(String[]::new);
                    ok.setContent(responses.buildContent(components, new Annotation[0], mediaTypes, null, body));
                    missing.remove(path);
                } else if (ok.getContent().values().stream().anyMatch(media -> saysNothing(media.getSchema()))) {
                    throw new IllegalStateException(path + " returns no type, so CachedBodies must say what it serves");
                }
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("CachedBodies describes paths the API does not serve: " + missing);
        }
    }

    // Bytes come out as a string and `ResponseEntity<?>` as an object without properties.
    private static boolean saysNothing(Schema<?> schema) {
        return "byte".equals(schema.getFormat())
                || (schema.get$ref() == null && schema.getProperties() == null && schema.getItems() == null);
    }

    private static Type returnType(Class<?> service, String name, Class<?>... parameters) {
        try {
            return service.getMethod(name, parameters).getGenericReturnType();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
