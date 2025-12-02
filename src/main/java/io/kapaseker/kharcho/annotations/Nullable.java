package io.kapaseker.kharcho.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Nullable 注解用于标记一个元素可以为 null 值。
 * 这个注解是 JSpecify 规范的一部分，用于提供更精确的 null 安全类型检查。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE_USE,
    ElementType.TYPE_PARAMETER,
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.LOCAL_VARIABLE
})
public @interface Nullable {
    /**
     * 可选的值，用于指定当类型参数不可从上下文推断时的默认行为。
     *
     * @return 与该注解关联的值
     */
    String value() default "";
}

