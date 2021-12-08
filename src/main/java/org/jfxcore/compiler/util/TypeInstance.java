// Copyright (c) 2021, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler.util;

import javassist.CtClass;
import org.jfxcore.compiler.diagnostic.SourceInfo;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.jfxcore.compiler.util.ExceptionHelper.unchecked;

/**
 * Represents the instantiation tree of a type, in which all generic arguments are replaced with concrete types.
 */
public class TypeInstance {

    public enum WildcardType {
        NONE,
        ANY,
        LOWER,
        UPPER;

        public static WildcardType of(char wildcard) {
            return switch (wildcard) {
                case ' ' -> NONE;
                case '*' -> ANY;
                case '+' -> UPPER;
                case '-' -> LOWER;
                default -> throw new IllegalArgumentException("wildcard");
            };
        }
    }

    public enum AssignmentContext {
        STRICT,
        LOOSE
    }

    private final CtClass type;
    private final List<TypeInstance> arguments;
    private final List<TypeInstance> superTypes;
    private final int dimensions;
    private final WildcardType wildcard;

    public TypeInstance(CtClass type) {
        this(type, WildcardType.NONE);
    }

    public TypeInstance(CtClass type, WildcardType wildcard) {
        int dimensions = 0;
        while (type.isArray()) {
            type = unchecked(SourceInfo.none(), type::getComponentType);
            ++dimensions;
        }

        this.dimensions = dimensions;
        this.type = type;
        this.arguments = Collections.emptyList();
        this.superTypes = Collections.emptyList();
        this.wildcard = wildcard;
    }

    public TypeInstance(CtClass type, List<TypeInstance> arguments) {
        this(type, arguments, WildcardType.NONE);
    }

    public TypeInstance(CtClass type, List<TypeInstance> arguments, WildcardType wildcard) {
        checkArguments(type, arguments);

        int dimensions = 0;
        while (type.isArray()) {
            type = unchecked(SourceInfo.none(), type::getComponentType);
            ++dimensions;
        }

        this.dimensions = dimensions;
        this.type = type;
        this.arguments = arguments;
        this.superTypes = Collections.emptyList();
        this.wildcard = wildcard;
    }

    public TypeInstance(CtClass type, List<TypeInstance> arguments, List<TypeInstance> superTypes) {
        this(type, arguments, superTypes, WildcardType.NONE);
    }

    public TypeInstance(CtClass type, List<TypeInstance> arguments, List<TypeInstance> superTypes, WildcardType wildcard) {
        checkArguments(type, arguments);

        int dimensions = 0;
        while (type.isArray()) {
            type = unchecked(SourceInfo.none(), type::getComponentType);
            ++dimensions;
        }

        this.dimensions = dimensions;
        this.type = type;
        this.arguments = arguments;
        this.superTypes = superTypes;
        this.wildcard = wildcard;
    }

    public TypeInstance(CtClass type, int dimensions, List<TypeInstance> arguments, List<TypeInstance> superTypes) {
        this(type, dimensions, arguments, superTypes, WildcardType.NONE);
    }

    public TypeInstance(CtClass type, int dimensions, List<TypeInstance> arguments, List<TypeInstance> superTypes, WildcardType wildcard) {
        checkArguments(type, arguments);

        this.type = type;
        this.dimensions = dimensions;
        this.arguments = arguments;
        this.superTypes = superTypes;
        this.wildcard = wildcard;
    }

    private static void checkArguments(CtClass type, List<TypeInstance> arguments) {
        if ((type.isPrimitive() || TypeHelper.isPrimitiveBox(type)) && arguments.size() > 0) {
            throw new IllegalArgumentException("Primitive cannot be parameterized.");
        }
    }

    public boolean isArray() {
        return dimensions > 0;
    }

    public int getDimensions() {
        return dimensions;
    }

    public boolean isPrimitive() {
        return type.isPrimitive() && dimensions == 0;
    }

    public CtClass jvmType() {
        return type;
    }

    public String getName() {
        return toString(false, false);
    }

    public String getJavaName() {
        return toString(false, true);
    }

    public String getSimpleName() {
        return toString(true, false);
    }

    public List<TypeInstance> getArguments() {
        return arguments;
    }

    public List<TypeInstance> getSuperTypes() {
        return superTypes;
    }

    public WildcardType getWildcardType() {
        return wildcard;
    }

    public TypeInstance getComponentType() {
        if (!isArray()) {
            return this;
        }

        return new TypeInstance(type, 0, arguments, superTypes, wildcard);
    }

    /**
     * Determines whether the specified type can be converted to this type via any of the conversions
     * specified by {@link #isAssignableFrom(TypeInstance)}, as well as narrowing primitive conversions.
     */
    public boolean isConvertibleFrom(TypeInstance from) {
        if ((TypeHelper.isPrimitiveBox(type, from.type) || TypeHelper.isPrimitiveBox(from.type, type))
                && dimensions == 0 && from.dimensions == 0) {
            return true;
        }

        return isAssignableFrom(from);
    }

    /**
     * Determines whether the specified type can be converted to this type via any of the conversions
     * specified by {@link #isAssignableFrom(TypeInstance, AssignmentContext)}, assuming a loose
     * assignment context.
     */
    public boolean isAssignableFrom(TypeInstance from) {
        return isAssignableFrom(from, AssignmentContext.LOOSE);
    }

    /**
     * Determines whether the specified type can be converted to this type via any of the
     * following conversions:
     * <ol>
     *     <li>an identity conversion
     *     <li>a widening primitive conversion
     *     <li>a widening reference conversion
     * </ol>
     *
     * In a loose assignment context, the following conversions are also permitted:
     * <ol>
     *     <li>a boxing conversion, optionally followed by a widening reference conversion
     *     <li>an unboxing conversion, optionally followed by a widening primitive conversion
     * </ol>
     */
    public boolean isAssignableFrom(TypeInstance from, AssignmentContext context) {
        // Identity conversion
        if (equals(from)) {
            return true;
        }

        // Widening primitive conversion
        if (dimensions == 0 && from.dimensions == 0
                && TypeHelper.isNumericPrimitive(type) && TypeHelper.isNumeric(from.type)) {
            // In a loose assignment context, we assume an unboxing conversion has occurred
            CtClass fromType = context == AssignmentContext.LOOSE ? TypeHelper.getPrimitiveType(from.type) : from.type;

            if (TypeHelper.equals(type, CtClass.charType)) {
                return TypeHelper.equals(fromType, CtClass.charType);
            }

            if (TypeHelper.equals(type, CtClass.byteType)) {
                return TypeHelper.equals(fromType, CtClass.byteType);
            }

            if (TypeHelper.equals(type, CtClass.shortType)) {
                return TypeHelper.equals(fromType, CtClass.shortType)
                    || TypeHelper.equals(fromType, CtClass.byteType);
            }

            if (TypeHelper.equals(type, CtClass.intType)) {
                return TypeHelper.equals(fromType, CtClass.intType)
                    || TypeHelper.equals(fromType, CtClass.shortType)
                    || TypeHelper.equals(fromType, CtClass.charType)
                    || TypeHelper.equals(fromType, CtClass.byteType);
            }

            if (TypeHelper.equals(type, CtClass.longType)) {
                return TypeHelper.equals(fromType, CtClass.longType)
                    || TypeHelper.equals(fromType, CtClass.intType)
                    || TypeHelper.equals(fromType, CtClass.shortType)
                    || TypeHelper.equals(fromType, CtClass.charType)
                    || TypeHelper.equals(fromType, CtClass.byteType);
            }

            if (TypeHelper.equals(type, CtClass.floatType)) {
                return TypeHelper.equals(fromType, CtClass.floatType)
                    || TypeHelper.equals(fromType, CtClass.longType)
                    || TypeHelper.equals(fromType, CtClass.intType)
                    || TypeHelper.equals(fromType, CtClass.shortType)
                    || TypeHelper.equals(fromType, CtClass.charType)
                    || TypeHelper.equals(fromType, CtClass.byteType);
            }

            if (TypeHelper.equals(type, CtClass.doubleType)) {
                return TypeHelper.equals(fromType, CtClass.doubleType)
                    || TypeHelper.equals(fromType, CtClass.floatType)
                    || TypeHelper.equals(fromType, CtClass.longType)
                    || TypeHelper.equals(fromType, CtClass.intType)
                    || TypeHelper.equals(fromType, CtClass.shortType)
                    || TypeHelper.equals(fromType, CtClass.charType)
                    || TypeHelper.equals(fromType, CtClass.byteType);
            }

            return false;
        }

        // Unboxing conversion
        if (context == AssignmentContext.LOOSE && TypeHelper.isPrimitiveBox(from.type, type)) {
            return TypeHelper.equals(type, TypeHelper.getPrimitiveType(from.type));
        }

        // Boxing conversion, followed by optional widening reference conversion
        if (context == AssignmentContext.LOOSE && from.isPrimitive()) {
            return dimensions == 0 && unchecked(SourceInfo.none(),
                () -> TypeHelper.getBoxedType(from.type).subtypeOf(type));
        }

        if (dimensions != from.dimensions) {
            return dimensions == 0 || equals(Classes.ObjectType());
        }

        if (arguments.size() > 0 && arguments.size() != from.arguments.size()) {
            for (TypeInstance fromSuperType : from.superTypes) {
                if (isAssignableFrom(fromSuperType)) {
                    return true;
                }
            }

            return false;
        }

        if (!unchecked(SourceInfo.none(), () -> from.type.subtypeOf(type))) {
            return false;
        }

        for (int i = 0; i < arguments.size(); ++i) {
            WildcardType wildcard = arguments.get(i).wildcard;

            if (wildcard == WildcardType.LOWER && !arguments.get(i).subtypeOf(from.arguments.get(i))) {
                return false;
            }

            if (wildcard == WildcardType.UPPER && !from.arguments.get(i).subtypeOf(arguments.get(i))) {
                return false;
            }

            if (wildcard == WildcardType.NONE && !arguments.get(i).equals(from.arguments.get(i))) {
                return false;
            }
        }

        return true;
    }

    public boolean subtypeOf(TypeInstance other) {
        return unchecked(SourceInfo.none(), () -> {
            if (other.dimensions == 0 && other.equals(Classes.ObjectType())) {
                return true;
            }

            return other.dimensions == dimensions && type.subtypeOf(other.type);
        });
    }

    public boolean subtypeOf(CtClass other) {
        return unchecked(SourceInfo.none(), () -> {
            int otherDimensions = 0;
            CtClass o = other;

            while (o.isArray()) {
                o = o.getComponentType();
                ++otherDimensions;
            }

            if (otherDimensions == 0 && other.equals(Classes.ObjectType())) {
                return true;
            }

            return dimensions == otherDimensions && type.subtypeOf(o);
        });
    }

    public boolean equals(CtClass other) {
        return TypeHelper.equals(type, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeInstance that = (TypeInstance)o;
        if (arguments.size() != that.arguments.size()) return false;
        if (dimensions != that.dimensions) return false;
        return arguments.isEmpty() ? TypeHelper.equals(type, that.type) : equals(new HashSet<>(), that);
    }

    private boolean equals(Set<TypeInstance> set, TypeInstance other) {
        if (set.contains(this)) {
            return true;
        }

        set.add(this);

        if (!TypeHelper.equals(type, other.type) || arguments.size() != other.arguments.size()) {
            return false;
        }

        for (int i = 0; i < arguments.size(); ++i) {
            if (!arguments.get(i).equals(set, other.arguments.get(i))) {
                return false;
            }
        }

        return true;
    }

    protected String toString(boolean simpleNames, boolean javaNames) {
        if (wildcard == WildcardType.ANY) {
            return "?";
        }

        StringBuilder builder = new StringBuilder();
        switch (wildcard) {
            case LOWER -> builder.append("? super ");
            case UPPER -> builder.append("? extends ");
        }

        if (javaNames) {
            builder.append(NameHelper.getJavaClassName(SourceInfo.none(), type));
        } else if (simpleNames) {
            builder.append(type.getSimpleName());
        } else {
            builder.append(type.getName());
        }

        if (arguments.size() > 0) {
            builder.append('<');

            for (int i = 0; i < arguments.size(); ++i) {
                if (i > 0) {
                    builder.append(',');
                }

                builder.append(arguments.get(i).toString(simpleNames, javaNames));
            }

            builder.append('>');
        }

        builder.append("[]".repeat(dimensions));

        return builder.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(TypeHelper.hashCode(type), arguments.size(), superTypes.size());
    }

    @Override
    public String toString() {
        return getSimpleName();
    }

}
