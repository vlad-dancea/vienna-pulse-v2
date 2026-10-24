package com.vladdancea.viennapulse.api;

import static java.util.Objects.requireNonNull;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/// Tells springdoc which errors each endpoint answers, as the RFC 9457 problem details Spring writes.
/// The services throw them and [DatabaseFailureAdvice] returns them, so no method declares them.
class ErrorResponses implements OpenApiCustomizer {
    private static final String PROBLEM = "ProblemDetail";

    // Every endpoint reads the database, so every one can also fail as DatabaseFailureAdvice answers.
    private static final Map<String, Map<HttpStatus, String>> ERRORS = Map.of(
            "/api/lines",
            Map.of(
                    HttpStatus.BAD_REQUEST, "The date is not a day written as YYYY-MM-DD.",
                    HttpStatus.NOT_FOUND, "There is no timetable yet, or it does not cover the date."),
            "/api/stops/{id}/departures",
            Map.of(HttpStatus.NOT_FOUND, "There is no stop with this id."));

    @Override
    public void customise(OpenAPI openApi) {
        requireNonNull(openApi.getComponents()).addSchemas(PROBLEM, problemDetail());
        var missing = new HashSet<>(ERRORS.keySet());
        requireNonNull(openApi.getPaths()).forEach((path, item) -> {
            missing.remove(path);
            for (var operation : item.readOperations()) {
                var responses = operation.getResponses();
                ERRORS.getOrDefault(path, Map.of())
                        .forEach((status, description) ->
                                responses.addApiResponse(String.valueOf(status.value()), problem(description)));
                responses.addApiResponse("500", problem("Reading the timetable failed in a way no retry fixes."));
                responses.addApiResponse(
                        "503",
                        problem("The database did not answer in time. This passes on its own.")
                                .addHeaderObject(
                                        HttpHeaders.RETRY_AFTER,
                                        new Header()
                                                .required(true)
                                                .description("The seconds to wait before asking again.")
                                                .schema(new JsonSchema().typesItem("integer"))));
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ErrorResponses describes paths the API does not serve: " + missing);
        }
    }

    private static ApiResponse problem(String description) {
        var body = new MediaType().schema(new JsonSchema().$ref(PROBLEM));
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, body));
    }

    // Spring leaves out `type` while it is about:blank, the default the API never changes, and the
    // controller tests compare whole problem bodies with exactly these members.
    private static JsonSchema problemDetail() {
        var schema = new JsonSchema();
        schema.addType("object");
        schema.setDescription("An error as RFC 9457 describes it.");
        schema.setRequired(List.of("title", "status", "detail", "instance"));
        schema.addProperty("title", new JsonSchema().typesItem("string"));
        schema.addProperty("status", new JsonSchema().typesItem("integer"));
        schema.addProperty("detail", new JsonSchema().typesItem("string"));
        schema.addProperty("instance", new JsonSchema().typesItem("string").format("uri-reference"));
        return schema;
    }
}
