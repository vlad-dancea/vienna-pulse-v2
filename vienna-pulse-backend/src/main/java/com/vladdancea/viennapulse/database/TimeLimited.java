package com.vladdancea.viennapulse.database;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/// Selects the [org.springframework.jdbc.core.simple.JdbcClient] whose statements have a time limit.
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER})
public @interface TimeLimited {}
