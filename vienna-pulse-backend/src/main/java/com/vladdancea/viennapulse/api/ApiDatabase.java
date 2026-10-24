package com.vladdancea.viennapulse.api;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/// Selects the API's own [org.springframework.jdbc.core.simple.JdbcClient], whose queries have a time limit.
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER})
@interface ApiDatabase {}
