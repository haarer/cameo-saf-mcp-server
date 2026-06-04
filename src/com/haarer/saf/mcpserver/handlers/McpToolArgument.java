package com.haarer.saf.mcpserver.handlers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(McpToolArgument.Container.class)
public @interface McpToolArgument {
    String name();
    String type() default "string";
    String description() default "";
    boolean required() default false;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Container {
        McpToolArgument[] value();
    }
}
