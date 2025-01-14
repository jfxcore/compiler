// Copyright (c) 2022, 2024, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler.util;

import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.SignatureAttribute;
import javassist.bytecode.SyntheticAttribute;
import javassist.bytecode.annotation.Annotation;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.compiler.diagnostic.ErrorCode;
import org.jfxcore.compiler.diagnostic.MarkupException;
import org.jfxcore.compiler.diagnostic.SourceInfo;
import org.jfxcore.compiler.diagnostic.errors.GeneralErrors;
import org.jfxcore.compiler.diagnostic.errors.SymbolResolutionErrors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public class Resolver {

    private final SourceInfo sourceInfo;
    private final boolean cacheEnabled;

    public Resolver(SourceInfo sourceInfo) {
        this.sourceInfo = sourceInfo;
        this.cacheEnabled = true;
    }

    public Resolver(SourceInfo sourceInfo, boolean cacheEnabled) {
        this.sourceInfo = sourceInfo;
        this.cacheEnabled = cacheEnabled;
    }

    /**
     * Returns the class for the specified fully-qualified type name.
     *
     * @throws MarkupException if the class was not found.
     */
    public CtClass resolveClass(String fullyQualifiedName) {
        CtClass ctclass = tryResolveClass(fullyQualifiedName);
        if (ctclass != null) {
            return ctclass;
        }

        throw SymbolResolutionErrors.classNotFound(sourceInfo, fullyQualifiedName);
    }

    /**
     * Returns the class for the specified fully-qualified type name, or <code>null</code> if the class was not found.
     */
    public CtClass tryResolveClass(String fullyQualifiedName) {
        int dimensions = 0;

        while (fullyQualifiedName.endsWith("[]")) {
            fullyQualifiedName = fullyQualifiedName.substring(0, fullyQualifiedName.length() - 2);
            ++dimensions;
        }

        int generic = fullyQualifiedName.indexOf('<');
        if (generic >= 0) {
            fullyQualifiedName = fullyQualifiedName.substring(0, generic);
        }

        String[] parts;
        String className = fullyQualifiedName;

        do {
            try {
                return CompilationContext.getCurrent().getClassPool().get(className + "[]".repeat(dimensions));
            } catch (NotFoundException ignored) {
            }

            parts = className.split("\\.");
            className = java.lang.String.join(
                ".", Arrays.copyOf(parts, parts.length - 1)) + "$" + parts[parts.length - 1];
        } while (parts.length > 1);

        return null;
    }

    public CtClass tryResolveNestedClass(CtClass enclosingType, String nestedTypeName) {
        while (enclosingType != null) {
            CtClass ctclass = tryResolveClass(enclosingType.getName() + "." + nestedTypeName);
            if (ctclass != null) {
                return ctclass;
            }

            try {
                enclosingType = enclosingType.getSuperclass();
            } catch (NotFoundException e) {
                throw SymbolResolutionErrors.classNotFound(sourceInfo, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Returns the class for the specified type name.
     * If the type name is fully qualified, it is resolved directly; otherwise the name is resolved
     * against the document's import list.
     *
     * @throws MarkupException if the class was not found.
     */
    public CtClass resolveClassAgainstImports(String typeName) {
        CtClass ctclass = tryResolveClassAgainstImports(typeName);
        if (ctclass == null) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, typeName);
        }

        return ctclass;
    }

    /**
     * Returns the class for the specified unqualified type name, or <code>null</code> if the class was not found.
     */
    public CtClass tryResolveClassAgainstImports(String typeName) {
        List<String> imports = CompilationContext.getCurrent().getImports();

        for (String potentialClassName : getPotentialClassNames(imports, typeName)) {
            CtClass ctclass = tryResolveClass(potentialClassName);
            if (ctclass != null) {
                return ctclass;
            }
        }

        return null;
    }

    private List<String> getPotentialClassNames(Collection<String> imports, String typeName) {
        for (String imp : imports) {
            if (imp.endsWith("." + typeName)) {
                int lastDot = imp.lastIndexOf(".");
                return List.of(typeName, imp, imp.substring(0, lastDot) + "$" + imp.substring(lastDot + 1));
            }
        }

        List<String> res = new ArrayList<>();
        res.add(typeName);
        boolean javaLang = false;

        for (String imp : imports) {
            if (!javaLang && imp.equals("java.lang.*")) {
                javaLang = true;
            }

            if (imp.endsWith(".*")) {
                res.add(imp.replace("*", "") + typeName);
            }
        }

        if (!javaLang) {
            res.add("java.lang." + typeName);
        }

        return res;
    }

    /**
     * Returns the parameter types of the specified constructor or method.
     */
    public TypeInstance[] getParameterTypes(CtBehavior behavior, List<TypeInstance> invocationChain) {
        return getParameterTypes(behavior, invocationChain, List.of());
    }

    /**
     * Returns the parameter types of the specified constructor or method.
     */
    public TypeInstance[] getParameterTypes(CtBehavior behavior,
                                            List<TypeInstance> invocationChain,
                                            List<TypeInstance> providedArguments) {
        try {
            CacheKey key = new CacheKey("getParameterTypes", behavior, invocationChain, providedArguments);
            CacheEntry entry = getCache().get(key);
            if (entry.found() && cacheEnabled) {
                return (TypeInstance[])entry.value();
            }

            TypeInstance[] result;
            SignatureAttribute.MethodSignature methodSignature = getGenericMethodSignature(behavior);

            if (methodSignature == null) {
                CtClass[] paramTypes = behavior.getParameterTypes();
                result = new TypeInstance[paramTypes.length];

                for (int i = 0; i < paramTypes.length; ++i) {
                    result[i] = getTypeInstance(paramTypes[i]);
                    result[i].getArguments().clear();
                }
            } else {
                SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(behavior.getDeclaringClass());
                result = new TypeInstance[methodSignature.getParameterTypes().length];

                Map<String, TypeInstance> associatedTypeVariables = associateTypeVariables(
                    behavior, methodSignature, invocationChain, providedArguments);

                for (int i = 0; i < methodSignature.getParameterTypes().length; ++i) {
                    result[i] = Objects.requireNonNullElse(
                        invokeType(
                            behavior.getDeclaringClass(),
                            methodSignature.getParameterTypes()[i],
                            TypeInstance.WildcardType.NONE,
                            classSignature != null ? classSignature.getParameters() : EMPTY_TYPE_PARAMS,
                            methodSignature.getTypeParameters(),
                            invocationChain,
                            associatedTypeVariables),
                        TypeInstance.ObjectType());
                }
            }

            return getCache().put(key, result);
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        } catch (BadBytecode ex) {
            throw ExceptionHelper.unchecked(ex);
        }
    }

    public PropertyInfo resolveProperty(TypeInstance declaringClass, boolean allowQualifiedName, String... names) {
        PropertyInfo propertyInfo = tryResolveProperty(declaringClass, allowQualifiedName, names);
        if (propertyInfo == null) {
            throw SymbolResolutionErrors.propertyNotFound(
                sourceInfo, declaringClass.jvmType(), String.join(".", names));
        }

        return propertyInfo;
    }

    public PropertyInfo tryResolveProperty(TypeInstance declaringClass, boolean allowQualifiedName, String... names) {
        CacheKey key = new CacheKey("tryResolveProperty", declaringClass, allowQualifiedName, names);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (PropertyInfo)entry.value();
        }

        boolean validLocalName = true;
        String propertyName = names[names.length - 1];

        if (names.length > 1) {
            TypeInstance receiverClass = declaringClass;
            String className = String.join(".", Arrays.copyOf(names, names.length - 1));
            CtClass clazz = tryResolveClassAgainstImports(className);
            if (clazz != null) {
                TypeInstance type = getTypeInstance(clazz);
                validLocalName = allowQualifiedName && declaringClass.subtypeOf(type);
                declaringClass = type;
            } else {
                return null;
            }

            PropertyInfo propertyInfo = tryResolveStaticPropertyImpl(declaringClass, receiverClass, propertyName);
            if (propertyInfo != null) {
                return getCache().put(key, propertyInfo);
            }
        }

        // A regular (non-static) property needs a valid local name, otherwise we don't accept it.
        if (!validLocalName) {
            return getCache().put(key, null);
        }

        GetterInfo propertyGetter = tryResolvePropertyGetter(declaringClass.jvmType(), null, propertyName);
        TypeInstance typeInstance = null;
        TypeInstance observableTypeInstance = null;

        // If we have a property getter, extract the type of the generic parameter.
        // For primitive wrappers like ObservableBooleanValue, we define the type of the property
        // to be the primitive value type (i.e. not the boxed value type).
        if (propertyGetter != null) {
            observableTypeInstance = getTypeInstance(propertyGetter.method(), List.of(declaringClass));
            typeInstance = findObservableArgument(observableTypeInstance);
        }

        // Look for a getter that matches the previously determined property type.
        // If we didn't find a property getter in the previous step, this selects the first getter that
        // matches by name, or in case this is a static property, matches by name and signature.
        // We don't look for a getter if we matched the property getter verbatim, as this indicates that
        // the JavaFX Beans naming schema was not used, or the "Property" suffix was specified explicitly.
        CtMethod getter = null;
        if (propertyGetter == null || !propertyGetter.verbatim()) {
            getter = tryResolveGetter(declaringClass.jvmType(), propertyName, false,
                                      typeInstance != null ? typeInstance.jvmType() : null);
        }

        try {
            if (typeInstance == null && getter != null) {
                typeInstance = getTypeInstance(getter, List.of(declaringClass));
            }

            // If we still haven't been able to determine a property type, this means that we haven't
            // found a property getter, nor a regular getter. This means the property doesn't exist.
            if (typeInstance == null) {
                return getCache().put(key, null);
            }

            // We only look for a setter if we have a getter (i.e. write-only properties don't exist).
            CtMethod setter = null;
            if (getter != null) {
                setter = tryResolveSetter(declaringClass.jvmType(), propertyName, false, getter.getReturnType());
            }

            PropertyInfo propertyInfo = new PropertyInfo(
                propertyName, propertyGetter != null ? propertyGetter.method() : null, getter, setter,
                typeInstance, observableTypeInstance, declaringClass, false);

            return getCache().put(key, propertyInfo);
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        }
    }

    private PropertyInfo tryResolveStaticPropertyImpl(TypeInstance declaringType, TypeInstance receiverType,
                                                      String propertyName) {
        try {
            CtMethod getter = tryResolveStaticGetter(declaringType.jvmType(), receiverType.jvmType(), propertyName, false);
            GetterInfo propertyGetter = tryResolvePropertyGetter(declaringType.jvmType(), receiverType.jvmType(), propertyName);
            TypeInstance observableType = null;

            if (propertyGetter != null) {
                try {
                    observableType = getTypeInstance(propertyGetter.method(), List.of(declaringType), List.of(receiverType));
                } catch (MarkupException ex) {
                    if (ex.getDiagnostic().getCode() != ErrorCode.NUM_TYPE_ARGUMENTS_MISMATCH) {
                        throw ex;
                    }

                    observableType = getTypeInstance(propertyGetter.method(), List.of(declaringType));
                }
            }

            TypeInstance propertyType = observableType != null ? findObservableArgument(observableType) : null;

            // If the return type of the getter doesn't match the type of the property getter,
            // we discard the getter and only use the property getter.
            if (propertyType != null && getter != null
                    && !propertyType.equals(getTypeInstance(getter, List.of(declaringType)))) {
                getter = null;
            }

            // We also discard the getter if the property getter matched verbatim, as this indicates
            // that the JavaFX Beans naming scheme was not used.
            if (propertyGetter != null && propertyGetter.verbatim()) {
                getter = null;
            }

            if (propertyGetter == null && getter != null) {
                try {
                    propertyType = getTypeInstance(getter, List.of(declaringType), List.of(receiverType));
                } catch (MarkupException ex) {
                    if (ex.getDiagnostic().getCode() != ErrorCode.NUM_TYPE_ARGUMENTS_MISMATCH) {
                        throw ex;
                    }

                    propertyType = getTypeInstance(getter, List.of(declaringType));
                }
            }

            CtMethod setter = null;
            if (propertyType != null && getter != null) {
                setter = tryResolveStaticSetter(declaringType.jvmType(), receiverType.jvmType(),
                                                propertyName, false, propertyType.jvmType());
            }

            if (propertyGetter == null && getter == null) {
                return null;
            }

            return new PropertyInfo(
                propertyName, propertyGetter != null ? propertyGetter.method() : null, getter, setter,
                propertyType, observableType, declaringType, true);
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        }
    }

    /**
     * Returns the public field with the specified name.
     */
    public CtField resolveField(CtClass ctclass, String name) {
        CtField field = tryResolveField(ctclass, name);
        if (field == null) {
            throw SymbolResolutionErrors.memberNotFound(sourceInfo, ctclass, name);
        }

        return field;
    }

    /**
     * Returns the field with the specified name.
     */
    public CtField resolveField(CtClass ctclass, String name, boolean publicOnly) {
        CtField field = tryResolveField(ctclass, name, publicOnly);
        if (field == null) {
            throw SymbolResolutionErrors.memberNotFound(sourceInfo, ctclass, name);
        }

        return field;
    }

    /**
     * Returns the public field with the specified name, or {@code null} if no such public field can be found.
     */
    public CtField tryResolveField(CtClass ctclass, String name) {
        return tryResolveField(ctclass, name, true);
    }

    /**
     * Returns the field with the specified name, or {@code null} if no such field can be found.
     */
    public CtField tryResolveField(CtClass ctclass, String name, boolean publicOnly) {
        for (CtField field : ctclass.getDeclaredFields()) {
            if (publicOnly && Modifier.isPrivate(field.getModifiers())) {
                continue;
            }

            if (field.getName().equals(name)) {
                return field;
            }
        }

        CtClass superclass;
        try {
            superclass = ctclass.getSuperclass();
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        }

        if (superclass != null) {
            return tryResolveField(superclass, name, publicOnly);
        }

        return null;
    }

    /**
     * Returns the method that satisfies the predicate, or {@code null} if no such method can be found.
     */
    public CtMethod tryResolveMethod(CtClass ctclass, Predicate<CtMethod> predicate) {
        for (CtMethod method : ctclass.getMethods()) {
            if (predicate.test(method)) {
                return method;
            }
        }

        return null;
    }

    /**
     * Returns all methods that satisfy the predicate.
     */
    public CtMethod[] resolveMethods(CtClass ctclass, Predicate<CtMethod> predicate) {
        List<CtMethod> methods = new ArrayList<>();

        for (CtMethod method : ctclass.getMethods()) {
            if (predicate.test(method)) {
                methods.add(method);
            }
        }

        return methods.toArray(new CtMethod[0]);
    }

    /**
     * Returns a getter method with a name corresponding to the specified name.
     *
     * If {@code verbatim} is true, only returns methods that match the name exactly; otherwise, also returns getter
     * methods with names like getMethodName or isMethodName.
     *
     * If {@code returnType} is non-null, only getter methods returning this type are considered.
     */
    public CtMethod resolveGetter(CtClass type, String name, boolean verbatim, @Nullable CtClass returnType) {
        CtMethod method = tryResolveGetter(type, name, verbatim, returnType);
        if (method == null) {
            throw SymbolResolutionErrors.memberNotFound(sourceInfo, type, name);
        }

        return method;
    }

    /**
     * Returns a getter method with a name corresponding to the specified name.
     *
     * If {@code verbatim} is true, only returns methods that match the name exactly; otherwise, also returns getter
     * methods with names like getMethodName or isMethodName.
     *
     * If {@code returnType} is non-null, only getter methods returning this type are considered.
     *
     * @return The method, or {@code null} if no getter can be found.
     */
    public CtMethod tryResolveGetter(CtClass type, String name, boolean verbatim, @Nullable CtClass returnType) {
        CacheKey key = new CacheKey("tryResolveGetter", type, name, verbatim, returnType);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (CtMethod)entry.value();
        }

        String alt0, alt1;

        if (!verbatim && Character.isLowerCase(name.charAt(0))) {
            alt0 = NameHelper.getGetterName(name, false);
            alt1 = NameHelper.getGetterName(name, true);
        } else {
            alt0 = null;
            alt1 = null;
        }

        CtMethod method = findMethod(type, m -> {
            try {
                CtClass ret = m.getReturnType();

                if (Modifier.isPublic(m.getModifiers())
                        && (m.getName().equals(name) || (!verbatim && (m.getName().equals(alt0) || m.getName().equals(alt1))))
                        && m.getParameterTypes().length == 0
                        && !TypeHelper.equals(ret, CtClass.voidType)
                        && (returnType == null || TypeHelper.equals(returnType, ret))
                        && !isSynthetic(m)) {
                    return true;
                }
            } catch (NotFoundException ignored) {
            }

            return false;
        });

        getCache().put(key, method);
        return method;
    }

    /**
     * Returns a setter method with a name corresponding to the specified name.
     *
     * If {@code verbatim} is true, only returns methods that match the name exactly; otherwise, also returns setter
     * methods with names like setMethodName.
     *
     * If {@code paramType} is non-null, only setter methods accepting this parameter type are considered.
     *
     * @return The method, or {@code null} if no setter can be found.
     */
    public CtMethod tryResolveSetter(CtClass type, String name, boolean verbatim, @Nullable CtClass paramType) {
        CacheKey key = new CacheKey("tryResolveSetter", type, name, verbatim, paramType);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (CtMethod)entry.value();
        }

        String setterName;

        if (!verbatim && Character.isLowerCase(name.charAt(0))) {
            setterName = NameHelper.getSetterName(name);
        } else {
            setterName = null;
        }

        CtMethod method = findMethod(type, m -> {
            try {
                if (Modifier.isPublic(m.getModifiers())
                        && (m.getName().equals(name) || (!verbatim && m.getName().equals(setterName)))
                        && m.getParameterTypes().length == 1
                        && TypeHelper.equals(m.getReturnType(), CtClass.voidType)
                        && (paramType == null || TypeHelper.equals(paramType, m.getParameterTypes()[0]))
                        && !isSynthetic(m)) {
                    return true;
                }
            } catch (NotFoundException ignored) {
            }

            return false;
        });

        getCache().put(key, method);
        return method;
    }

    /**
     * Returns a static property setter method with a name corresponding to the specified name.
     *
     * If {@code verbatim} is true, only returns methods that match the name exactly; otherwise, also returns setter
     * methods with names like setMethodName.
     *
     * If {@code paramType} is non-null, only setter methods accepting this parameter type are considered.
     *
     * @return The method, or {@code null} if no setter can be found.
     */
    public CtMethod tryResolveStaticSetter(
            CtClass declaringClass, CtClass receiverClass, String name, boolean verbatim, @Nullable CtClass paramType) {
        CacheKey key = new CacheKey("tryResolveStaticSetter", declaringClass, receiverClass, name, paramType);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (CtMethod)entry.value();
        }

        String setterName;

        if (!verbatim && Character.isLowerCase(name.charAt(0))) {
            setterName = NameHelper.getSetterName(name);
        } else {
            setterName = null;
        }

        CtMethod method = findMethod(declaringClass, m -> {
            try {
                if (Modifier.isPublic(m.getModifiers())
                        && (m.getName().equals(name) || (!verbatim && m.getName().equals(setterName)))
                        && m.getParameterTypes().length == 2
                        && receiverClass.subtypeOf(m.getParameterTypes()[0])
                        && TypeHelper.equals(m.getReturnType(), CtClass.voidType)
                        && (paramType == null || TypeHelper.equals(m.getParameterTypes()[1], paramType))
                        && !isSynthetic(m)) {
                    return true;
                }
            } catch (NotFoundException ignored) {
            }

            return false;
        });

        getCache().put(key, method);
        return method;
    }

    /**
     * Returns the getter for the specified static property (i.e. a method like GridPane.getRowIndex).
     *
     * @param declaringClass the class that declares the getter method
     * @param receiverClass the parameter of the getter method (usually a subclass of Node)
     * @param name the name of the property
     * @param verbatim if {@code true}, only methods matching the name exactly are considered
     * @return The method, or {@code null} if no getter can be found.
     */
    public CtMethod tryResolveStaticGetter(
            CtClass declaringClass, CtClass receiverClass, String name, boolean verbatim) {
        CacheKey key = new CacheKey("tryResolveStaticGetter", declaringClass, name, verbatim);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (CtMethod)entry.value();
        }

        String alt0, alt1;

        if (!verbatim && Character.isLowerCase(name.charAt(0))) {
            alt0 = NameHelper.getGetterName(name, false);
            alt1 = NameHelper.getGetterName(name, true);
        } else {
            alt0 = null;
            alt1 = null;
        }

        CtMethod method = findMethod(declaringClass, m -> {
            int modifiers = m.getModifiers();

            try {
                if (Modifier.isPublic(modifiers)
                        && Modifier.isStatic(modifiers)
                        && (m.getName().equals(name) || (!verbatim && (m.getName().equals(alt0) || m.getName().equals(alt1))))
                        && m.getParameterTypes().length == 1
                        && receiverClass.subtypeOf(m.getParameterTypes()[0])
                        && !TypeHelper.equals(m.getReturnType(), CtClass.voidType)
                        && !isSynthetic(m)) {
                    return true;
                }
            } catch (NotFoundException ignored) {
            }

            return false;
        });

        getCache().put(key, method);
        return method;
    }

    private record GetterInfo(CtMethod method, boolean verbatim) {}

    /**
     * Returns a property getter method for the specified property (i.e. a method like Button.textProperty()).
     *
     * @return The method, or {@code null} if no getter can be found.
     */
    private GetterInfo tryResolvePropertyGetter(
            CtClass declaringClass, @Nullable CtClass receiverClass, String name) {
        CacheKey key = new CacheKey("tryResolvePropertyGetter", declaringClass, receiverClass, name);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (GetterInfo)entry.value();
        }

        List<String> potentialNames = new ArrayList<>(4);
        potentialNames.add(name);
        potentialNames.add(NameHelper.getPropertyGetterName(name));

        if (Character.isLowerCase(name.charAt(0))) {
            potentialNames.add(NameHelper.getGetterName(name, false));
            potentialNames.add(NameHelper.getGetterName(name, true));
        }

        CtMethod method = potentialNames.stream()
            .map(n -> resolvePropertyGetterImpl(declaringClass, receiverClass, n))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        GetterInfo getter = method != null ? new GetterInfo(method, method.getName().equals(name)) : null;
        getCache().put(key, getter);
        return getter;
    }

    private CtMethod resolvePropertyGetterImpl(
            CtClass declaringClass, @Nullable CtClass receiverClass, String methodName) {
        return findMethod(declaringClass, m -> {
            int modifiers = m.getModifiers();

            try {
                if (Modifier.isPublic(modifiers)
                        && (receiverClass == null || Modifier.isStatic(modifiers))
                        && m.getName().equals(methodName)
                        && m.getReturnType().subtypeOf(Classes.ObservableValueType())
                        && !isSynthetic(m)) {
                    CtClass[] paramTypes = m.getParameterTypes();

                    if (receiverClass != null) {
                        return paramTypes.length == 1 && receiverClass.subtypeOf(paramTypes[0]);
                    } else {
                        return paramTypes.length == 0;
                    }
                }
            } catch (NotFoundException ignored) {
            }

            return false;
        });
    }

    /**
     * Returns the class annotation for the specified name, or <code>null</code> if the annotation was not found.
     */
    public Annotation tryResolveClassAnnotation(CtClass type, String annotationName) {
        CacheKey key = new CacheKey("tryResolveClassAnnotation", type, annotationName);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (Annotation)entry.value();
        }

        Annotation annotation = tryResolveClassAnnotationRecursive(type, annotationName, true);
        if (annotation == null) {
            annotation = tryResolveClassAnnotationRecursive(type, annotationName, false);
        }

        getCache().put(key, annotation);
        return annotation;
    }

    private Annotation tryResolveClassAnnotationRecursive(CtClass type, String annotationName, boolean runtimeVisible) {
        AnnotationsAttribute attr = null;

        try {
            attr = (AnnotationsAttribute)type.getClassFile().getAttribute(
                runtimeVisible ? AnnotationsAttribute.visibleTag : AnnotationsAttribute.invisibleTag);

            if (attr == null || attr.getAnnotation(annotationName) == null) {
                try {
                    CtClass superclass = type.getSuperclass();
                    if (superclass != null) {
                        return tryResolveClassAnnotationRecursive(superclass, annotationName, runtimeVisible);
                    }
                } catch (NotFoundException ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return attr != null ? attr.getAnnotation(annotationName) : null;
    }

    /**
     * Returns the method annotation for the specified name, or <code>null</code> if the annotation was not found.
     */
    public Annotation tryResolveMethodAnnotation(CtBehavior method, String annotationName, boolean simpleName) {
        AnnotationsAttribute attr = (AnnotationsAttribute)method
            .getMethodInfo2()
            .getAttribute(AnnotationsAttribute.visibleTag);

        if (attr == null) {
            attr = (AnnotationsAttribute)method
                .getMethodInfo2()
                .getAttribute(AnnotationsAttribute.invisibleTag);
        }

        if (attr == null){
            return null;
        }

        if (simpleName) {
            for (Annotation annotation : attr.getAnnotations()) {
                String[] names = annotation.getTypeName().split("\\.");
                if (names[names.length - 1].equals(annotationName)) {
                    return annotation;
                }
            }
        }

        return attr.getAnnotation(annotationName);
    }

    public CtClass getObservableClass(CtClass type, boolean requestProperty) {
        try {
            if (type == CtClass.booleanType || type == Classes.BooleanType()
                    || type.subtypeOf(Classes.ObservableBooleanValueType())) {
                return requestProperty && type.subtypeOf(Classes.BooleanPropertyType()) ?
                    Classes.BooleanPropertyType() : Classes.ObservableBooleanValueType();
            } else if (type == CtClass.intType || type == Classes.IntegerType()
                    || type == CtClass.shortType || type == Classes.ShortType()
                    || type == CtClass.byteType || type == Classes.ByteType()
                    || type == CtClass.charType || type == Classes.CharacterType()
                    || type.subtypeOf(Classes.ObservableIntegerValueType())) {
                return requestProperty && type.subtypeOf(Classes.IntegerPropertyType()) ?
                    Classes.IntegerPropertyType() : Classes.ObservableIntegerValueType();
            } else if (type == CtClass.longType || type == Classes.LongType()
                    || type.subtypeOf(Classes.ObservableLongValueType())) {
                return requestProperty && type.subtypeOf(Classes.LongPropertyType()) ?
                    Classes.LongPropertyType() : Classes.ObservableLongValueType();
            } else if (type == CtClass.floatType || type == Classes.FloatType()
                    || type.subtypeOf(Classes.ObservableFloatValueType())) {
                return requestProperty && type.subtypeOf(Classes.FloatPropertyType()) ?
                    Classes.FloatPropertyType() : Classes.ObservableFloatValueType();
            } else if (type == CtClass.doubleType || type == Classes.DoubleType()
                    || type.subtypeOf(Classes.ObservableDoubleValueType())) {
                return requestProperty && type.subtypeOf(Classes.DoublePropertyType()) ?
                    Classes.DoublePropertyType() : Classes.ObservableDoubleValueType();
            } else {
                return requestProperty && type.subtypeOf(Classes.PropertyType()) ?
                    Classes.PropertyType() : Classes.ObservableValueType();
            }
        } catch (NotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    public TypeInstance getObservableClass(TypeInstance type) {
        if (type.equals(CtClass.booleanType) || type.equals(Classes.BooleanType())) {
            return getTypeInstance(Classes.ObservableBooleanValueType());
        } else if (type.equals(CtClass.intType) || type.equals(Classes.IntegerType())
                || type.equals(CtClass.shortType) || type.equals(Classes.ShortType())
                || type.equals(CtClass.byteType) || type.equals(Classes.ByteType())
                || type.equals(CtClass.charType) || type.equals(Classes.CharacterType())) {
            return getTypeInstance(Classes.ObservableIntegerValueType());
        } else if (type.equals(CtClass.longType) || type.equals(Classes.LongType())) {
            return getTypeInstance(Classes.ObservableLongValueType());
        } else if (type.equals(CtClass.floatType) || type.equals(Classes.FloatType())) {
            return getTypeInstance(Classes.ObservableFloatValueType());
        } else if (type.equals(CtClass.doubleType) || type.equals(Classes.DoubleType())) {
            return getTypeInstance(Classes.ObservableDoubleValueType());
        } else {
            return getTypeInstance(Classes.ObservableValueType(), List.of(type));
        }
    }

    public TypeInstance getTypeInstance(CtClass clazz) {
        CacheKey key = new CacheKey("getTypeInstance", clazz);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (TypeInstance)entry.value();
        }

        try {
            TypeInstance typeInstance = invokeType(clazz.getDeclaringClass(), clazz, Map.of());
            return getCache().put(key, typeInstance);
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        } catch (BadBytecode ex) {
            throw ExceptionHelper.unchecked(ex);
        }
    }

    public TypeInstance getTypeInstance(CtClass clazz, List<TypeInstance> arguments) {
        CacheKey key = new CacheKey("getTypeInstance", clazz, arguments);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (TypeInstance)entry.value();
        }

        try {
            SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(clazz);
            if (classSignature == null) {
                if (!arguments.isEmpty()) {
                    throw GeneralErrors.numTypeArgumentsMismatch(sourceInfo, clazz, 0, arguments.size());
                }

                return getTypeInstance(clazz);
            }

            Map<String, TypeInstance> providedArguments = associateProvidedArguments(
                clazz, arguments, classSignature.getParameters(), EMPTY_TYPE_PARAMS);

            return getCache().put(key, invokeType(clazz.getDeclaringClass(), clazz, providedArguments));
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        } catch (BadBytecode ex) {
            throw ExceptionHelper.unchecked(ex);
        }
    }

    public TypeInstance getTypeInstance(CtField field, List<TypeInstance> invocationChain) {
        CacheKey key = new CacheKey("getTypeInstance", field, invocationChain);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (TypeInstance)entry.value();
        }

        try {
            SignatureAttribute.ObjectType fieldType = getGenericFieldSignature(field);
            if (fieldType == null) {
                return invokeType(field.getDeclaringClass(), field.getType(), Map.of());
            }

            SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(field.getDeclaringClass());
            SignatureAttribute.TypeParameter[] classTypeParams = classSignature != null ?
                classSignature.getParameters() : EMPTY_TYPE_PARAMS;

            TypeInstance typeInstance = invokeType(
                field.getDeclaringClass(),
                fieldType,
                TypeInstance.WildcardType.NONE,
                classTypeParams,
                new SignatureAttribute.TypeParameter[0],
                invocationChain,
                Map.of());

            return getCache().put(key, Objects.requireNonNullElse(typeInstance, TypeInstance.ObjectType()));
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        } catch (BadBytecode ex) {
            throw ExceptionHelper.unchecked(ex);
        }
    }

    public TypeInstance getTypeInstance(CtBehavior method, List<TypeInstance> invocationChain) {
        return getTypeInstance(method, invocationChain, List.of());
    }

    public TypeInstance getTypeInstance(CtBehavior behavior,
                                        List<TypeInstance> invocationChain,
                                        List<TypeInstance> providedArguments) {
        CacheKey key = new CacheKey("getTypeInstance", behavior, invocationChain, providedArguments);
        CacheEntry entry = getCache().get(key);
        if (entry.found() && cacheEnabled) {
            return (TypeInstance)entry.value();
        }

        if (behavior instanceof CtConstructor constructor) {
            return getTypeInstance(constructor.getDeclaringClass(), providedArguments);
        }

        CtMethod method = (CtMethod)behavior;

        try {
            SignatureAttribute.MethodSignature methodSignature = getGenericMethodSignature(method);
            SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(method.getDeclaringClass());
            TypeInstance typeInstance;

            if (methodSignature == null) {
                if (!providedArguments.isEmpty()) {
                    throw GeneralErrors.numTypeArgumentsMismatch(sourceInfo, method, 0, providedArguments.size());
                }

                typeInstance = invokeType(method.getDeclaringClass(), method.getReturnType(), Map.of());
            } else {
                SignatureAttribute.TypeParameter[] classTypeParams =
                    classSignature != null ? classSignature.getParameters() : EMPTY_TYPE_PARAMS;

                typeInstance = invokeType(
                    method.getDeclaringClass(),
                    methodSignature.getReturnType(),
                    TypeInstance.WildcardType.NONE,
                    classTypeParams,
                    methodSignature.getTypeParameters(),
                    invocationChain,
                    associateProvidedArguments(
                        method,
                        providedArguments,
                        methodSignature.getTypeParameters()));
            }

            return getCache().put(key, Objects.requireNonNullElse(typeInstance, TypeInstance.ObjectType()));
        } catch (NotFoundException ex) {
            throw SymbolResolutionErrors.classNotFound(sourceInfo, ex.getMessage());
        } catch (BadBytecode ex) {
            throw ExceptionHelper.unchecked(ex);
        }
    }

    public TypeInstance tryFindArgument(TypeInstance actualType, CtClass targetType) {
        if (TypeHelper.equals(actualType.jvmType(), targetType)) {
            return actualType.getArguments().size() == 0 ? null : actualType.getArguments().get(0);
        }

        for (TypeInstance superType : actualType.getSuperTypes()) {
            TypeInstance argType = tryFindArgument(superType, targetType);
            if (argType != null) {
                return argType;
            }
        }

        return null;
    }

    public TypeInstance findObservableArgument(TypeInstance propertyType) {
        TypeInstance typeArg = tryFindObservableArgument(propertyType);
        if (typeArg == null) {
            throw new IllegalArgumentException("propertyType");
        }

        return typeArg;
    }

    public @Nullable TypeInstance tryFindObservableArgument(TypeInstance propertyType) {
        if (propertyType.subtypeOf(Classes.ObservableBooleanValueType())) {
            return TypeInstance.booleanType();
        } else if (propertyType.subtypeOf(Classes.ObservableIntegerValueType())) {
            return TypeInstance.intType();
        } else if (propertyType.subtypeOf(Classes.ObservableLongValueType())) {
            return TypeInstance.longType();
        } else if (propertyType.subtypeOf(Classes.ObservableFloatValueType())) {
            return TypeInstance.floatType();
        } else if (propertyType.subtypeOf(Classes.ObservableDoubleValueType())) {
            return TypeInstance.doubleType();
        } else if (TypeHelper.equals(propertyType.jvmType(), Classes.ObservableValueType())) {
            return propertyType.getArguments().isEmpty() ?
                TypeInstance.ObjectType() : propertyType.getArguments().get(0);
        }

        for (TypeInstance superType : propertyType.getSuperTypes()) {
            TypeInstance argType = tryFindObservableArgument(superType);
            if (argType != null) {
                return argType;
            }
        }

        return null;
    }

    public TypeInstance findWritableArgument(TypeInstance propertyType) {
        TypeInstance typeArg = tryFindWritableArgument(propertyType);
        if (typeArg == null) {
            throw new IllegalArgumentException("propertyType");
        }

        return typeArg;
    }

    public @Nullable TypeInstance tryFindWritableArgument(TypeInstance propertyType) {
        if (propertyType.subtypeOf(Classes.WritableBooleanValueType())) {
            return TypeInstance.booleanType();
        } else if (propertyType.subtypeOf(Classes.WritableIntegerValueType())) {
            return TypeInstance.intType();
        } else if (propertyType.subtypeOf(Classes.WritableLongValueType())) {
            return TypeInstance.longType();
        } else if (propertyType.subtypeOf(Classes.WritableFloatValueType())) {
            return TypeInstance.floatType();
        } else if (propertyType.subtypeOf(Classes.WritableDoubleValueType())) {
            return TypeInstance.doubleType();
        } else if (TypeHelper.equals(propertyType.jvmType(), Classes.WritableValueType())) {
            return propertyType.getArguments().isEmpty() ?
                TypeInstance.ObjectType() : propertyType.getArguments().get(0);
        }

        for (TypeInstance superType : propertyType.getSuperTypes()) {
            TypeInstance argType = tryFindWritableArgument(superType);
            if (argType != null) {
                return argType;
            }
        }

        return null;
    }

    private SignatureAttribute.ObjectType getGenericFieldSignature(CtField field) throws BadBytecode {
        String signature = field.getGenericSignature();
        return signature != null ? SignatureAttribute.toFieldSignature(signature) : null;
    }

    private SignatureAttribute.ClassSignature getGenericClassSignature(CtClass clazz) throws BadBytecode {
        String signature = clazz.getGenericSignature();
        return signature != null ? SignatureAttribute.toClassSignature(signature) : null;
    }

    private SignatureAttribute.MethodSignature getGenericMethodSignature(CtBehavior method) throws BadBytecode {
        String signature = method.getGenericSignature();
        return signature != null ? SignatureAttribute.toMethodSignature(signature) : null;
    }

    private Map<String, TypeInstance> associateTypeVariables(
            CtBehavior behavior,
            SignatureAttribute.MethodSignature methodSignature,
            List<TypeInstance> invocationChain,
            List<TypeInstance> providedArguments)
                throws NotFoundException, BadBytecode {
        class Algorithms {
            static TypeInstance findTypeInstance(TypeInstance typeInstance, CtClass type) {
                if (typeInstance.equals(type)) {
                    return typeInstance;
                }

                for (TypeInstance superType : typeInstance.getSuperTypes()) {
                    typeInstance = findTypeInstance(superType, type);
                    if (typeInstance != null) {
                        return typeInstance;
                    }
                }

                return null;
            }
        }

        Map<String, TypeInstance> result = new HashMap<>();
        SignatureAttribute.TypeParameter[] methodTypeParams = methodSignature.getTypeParameters();

        if (methodTypeParams.length != providedArguments.size()) {
            throw GeneralErrors.numTypeArgumentsMismatch(
                    sourceInfo, behavior, methodTypeParams.length, providedArguments.size());
        }

        for (int i = 0; i < methodTypeParams.length; ++i) {
            result.put(methodTypeParams[i].getName(), providedArguments.get(i));
        }

        for (int i = 0; i < methodTypeParams.length; ++i) {
            checkProvidedArgument(
                providedArguments.get(i), methodTypeParams[i], EMPTY_TYPE_PARAMS, methodTypeParams, result);
        }

        CtClass declaringClass = behavior.getDeclaringClass();
        SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(declaringClass);
        if (classSignature == null) {
            return result;
        }

        for (SignatureAttribute.Type paramType : methodSignature.getParameterTypes()) {
            if (!(paramType instanceof SignatureAttribute.TypeVariable typeVar)
                    || result.containsKey(typeVar.getName())) {
                continue;
            }

            for (int i = invocationChain.size() - 1; i >= 0; --i) {
                TypeInstance invokingType = Algorithms.findTypeInstance(invocationChain.get(i), declaringClass);
                if (invokingType == null) {
                    continue;
                }

                for (int j = 0; j < classSignature.getParameters().length; ++j) {
                    SignatureAttribute.TypeParameter typeParam = classSignature.getParameters()[j];
                    if (!typeParam.getName().equals(typeVar.getName())) {
                        continue;
                    }

                    result.put(typeVar.getName(), invokingType.getArguments().get(j));
                }
            }
        }

        return result;
    }

    private Map<String, TypeInstance> associateProvidedArguments(
            CtClass type,
            List<TypeInstance> providedArguments,
            SignatureAttribute.TypeParameter[] classTypeParams,
            SignatureAttribute.TypeParameter[] methodTypeParams)
                throws NotFoundException, BadBytecode {
        if (providedArguments.isEmpty()) {
            return Map.of();
        }

        var typeParams = Arrays.copyOf(classTypeParams, classTypeParams.length + methodTypeParams.length);
        System.arraycopy(methodTypeParams, 0, typeParams, classTypeParams.length, methodTypeParams.length);

        if (typeParams.length != providedArguments.size()) {
            throw GeneralErrors.numTypeArgumentsMismatch(
                sourceInfo, type, typeParams.length, providedArguments.size());
        }

        Map<String, TypeInstance> result = new HashMap<>();

        for (int i = 0; i < typeParams.length; ++i) {
            result.put(typeParams[i].getName(), providedArguments.get(i));
        }

        for (int i = 0; i < typeParams.length; ++i) {
            checkProvidedArgument(providedArguments.get(i), typeParams[i], classTypeParams, methodTypeParams, result);
        }

        return result;
    }

    private Map<String, TypeInstance> associateProvidedArguments(
            CtBehavior behavior,
            List<TypeInstance> providedArguments,
            SignatureAttribute.TypeParameter[] methodTypeParams)
                throws NotFoundException, BadBytecode {
        if (providedArguments.isEmpty()) {
            return Map.of();
        }

        if (methodTypeParams.length != providedArguments.size()) {
            throw GeneralErrors.numTypeArgumentsMismatch(
                sourceInfo, behavior, methodTypeParams.length, providedArguments.size());
        }

        Map<String, TypeInstance> result = new HashMap<>();

        for (int i = 0; i < methodTypeParams.length; ++i) {
            result.put(methodTypeParams[i].getName(), providedArguments.get(i));
        }

        for (int i = 0; i < methodTypeParams.length; ++i) {
            checkProvidedArgument(
                providedArguments.get(i), methodTypeParams[i], EMPTY_TYPE_PARAMS, methodTypeParams, result);
        }

        return result;
    }

    private void checkProvidedArgument(
            TypeInstance argumentType,
            SignatureAttribute.TypeParameter requiredType,
            SignatureAttribute.TypeParameter[] classTypeParams,
            SignatureAttribute.TypeParameter[] methodTypeParams,
            Map<String, TypeInstance> providedArguments)
                throws NotFoundException, BadBytecode {
        TypeInstance bound = requiredType.getClassBound() != null ?
            invokeType(
                null, requiredType.getClassBound(), TypeInstance.WildcardType.NONE,
                classTypeParams, methodTypeParams, List.of(), providedArguments) :
            invokeType(
                null, requiredType.getInterfaceBound()[0], TypeInstance.WildcardType.NONE,
                classTypeParams, methodTypeParams, List.of(), providedArguments);

        if (bound != null && !bound.isAssignableFrom(argumentType)) {
            throw GeneralErrors.typeArgumentOutOfBound(sourceInfo, argumentType, bound);
        }
    }

    private TypeInstance invokeType(CtClass invokingClass, CtClass invokedClass,
                                    Map<String, TypeInstance> providedArguments)
            throws NotFoundException, BadBytecode {
        SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(invokedClass);
        List<TypeInstance> arguments = new ArrayList<>();
        List<TypeInstance> superTypes = new ArrayList<>();
        TypeInstance invokedTypeInstance = new TypeInstance(
            invokedClass, arguments, superTypes, TypeInstance.WildcardType.NONE);

        if (classSignature != null) {
            if (providedArguments.size() > 0 && providedArguments.size() != classSignature.getParameters().length) {
                throw GeneralErrors.numTypeArgumentsMismatch(
                    sourceInfo, invokedClass, classSignature.getParameters().length, providedArguments.size());
            }

            for (int i = 0; i < classSignature.getParameters().length; ++i) {
                SignatureAttribute.TypeParameter typeParam = classSignature.getParameters()[i];

                if (providedArguments.size() > 0) {
                    arguments.add(providedArguments.get(typeParam.getName()));
                } else {
                    TypeInstance bound = null;

                    if (typeParam.getClassBound() != null) {
                        bound = invokeType(
                            invokingClass, typeParam.getClassBound(), TypeInstance.WildcardType.NONE,
                            EMPTY_TYPE_PARAMS, EMPTY_TYPE_PARAMS, Collections.emptyList(), providedArguments);
                    } else if (typeParam.getInterfaceBound().length > 0) {
                        bound = invokeType(
                            invokingClass, typeParam.getInterfaceBound()[0], TypeInstance.WildcardType.NONE,
                            EMPTY_TYPE_PARAMS, EMPTY_TYPE_PARAMS, Collections.emptyList(), providedArguments);
                    }

                    if (bound != null) {
                        arguments.add(TypeInstance.ofErased(bound));
                    } else {
                        arguments.add(TypeInstance.ofErased(TypeInstance.ObjectType()));
                    }
                }
            }

            TypeInstance superType = invokeType(
                invokedClass,
                classSignature.getSuperClass(),
                TypeInstance.WildcardType.NONE,
                classSignature.getParameters(),
                EMPTY_TYPE_PARAMS,
                List.of(invokedTypeInstance),
                Map.of());

            if (superType != null) {
                superTypes.add(superType);
            }

            for (SignatureAttribute.ClassType intfType : classSignature.getInterfaces()) {
                superType = invokeType(
                    invokedClass,
                    intfType,
                    TypeInstance.WildcardType.NONE,
                    classSignature.getParameters(),
                    EMPTY_TYPE_PARAMS,
                    List.of(invokedTypeInstance),
                    Map.of());

                if (superType != null) {
                    superTypes.add(superType);
                }
            }
        } else {
            if (invokedClass.getSuperclass() != null) {
                superTypes.add(invokeType(null, invokedClass.getSuperclass(), Map.of()));
            }

            for (CtClass intfClass : invokedClass.getInterfaces()) {
                superTypes.add(invokeType(null, intfClass, Map.of()));
            }
        }

        return invokedTypeInstance;
    }

    private @Nullable TypeInstance invokeType(
            CtClass invokingClass,
            SignatureAttribute.Type invokedType,
            TypeInstance.WildcardType wildcard,
            SignatureAttribute.TypeParameter[] classTypeParams,
            SignatureAttribute.TypeParameter[] methodTypeParams,
            List<TypeInstance> invocationChain,
            Map<String, TypeInstance> providedArguments)
                throws NotFoundException, BadBytecode {
        if (invokedType instanceof SignatureAttribute.BaseType baseType) {
            return new TypeInstance(baseType.getCtlass(), List.of(), List.of(), wildcard);
        }

        if (invokedType instanceof SignatureAttribute.ArrayType arrayType) {
            SignatureAttribute.Type componentType = arrayType.getComponentType();
            int dimension = arrayType.getDimension();
            TypeInstance typeInst = invokeType(
                invokingClass, componentType, TypeInstance.WildcardType.NONE, classTypeParams,
                methodTypeParams, invocationChain, providedArguments);

            return typeInst.withDimensions(dimension).withWildcard(wildcard);
        }

        if (invokedType instanceof SignatureAttribute.ClassType classType) {
            CtClass clazz = resolveClass(invokedType.jvmTypeName());
            TypeInstance typeInstance;
            SignatureAttribute.ClassSignature classSignature = getGenericClassSignature(clazz);

            if (classSignature != null) {
                List<TypeInstance> arguments;

                if (classType.getTypeArguments() == null || providedArguments.isEmpty()) {
                    arguments = invokeTypeArguments(
                        invokingClass, classType, classTypeParams, methodTypeParams, invocationChain);
                } else {
                    arguments = new ArrayList<>();

                    for (SignatureAttribute.TypeArgument typeArg : classType.getTypeArguments()) {
                        TypeInstance typeInst;

                        if (typeArg.isWildcard() && typeArg.getType() == null) {
                            typeInst = new TypeInstance(
                                Classes.ObjectType(),
                                TypeInstance.ObjectType().getArguments(),
                                TypeInstance.ObjectType().getSuperTypes(),
                                TypeInstance.WildcardType.ANY);
                        } else {
                            typeInst = invokeType(
                                invokingClass, typeArg.getType(), TypeInstance.WildcardType.of(typeArg.getKind()),
                                classTypeParams, methodTypeParams, invocationChain, providedArguments);
                        }

                        arguments.add(Objects.requireNonNull(typeInst));
                    }
                }

                typeInstance = new TypeInstance(clazz, arguments, new ArrayList<>(), wildcard);
                List<TypeInstance> extendedInvocationChain = new ArrayList<>(invocationChain.size() + 1);
                extendedInvocationChain.addAll(invocationChain);
                extendedInvocationChain.add(typeInstance);

                SignatureAttribute.TypeParameter[] typeParams = classSignature.getParameters();

                TypeInstance superType = invokeType(
                    clazz, classSignature.getSuperClass(), TypeInstance.WildcardType.NONE, typeParams,
                    EMPTY_TYPE_PARAMS, extendedInvocationChain, Map.of());

                if (superType != null) {
                    typeInstance.getSuperTypes().add(superType);
                }

                for (SignatureAttribute.ClassType intfClass : classSignature.getInterfaces()) {
                    superType = invokeType(
                        clazz, intfClass, TypeInstance.WildcardType.NONE, typeParams,
                        EMPTY_TYPE_PARAMS, extendedInvocationChain, Map.of());

                    if (superType != null) {
                        typeInstance.getSuperTypes().add(superType);
                    }
                }
            } else {
                typeInstance = new TypeInstance(clazz, Collections.emptyList(), new ArrayList<>(), wildcard);

                if (clazz.getSuperclass() != null) {
                    typeInstance.getSuperTypes().add(
                        invokeType(null, clazz.getSuperclass(), Map.of()));
                }

                for (CtClass intfClass : clazz.getInterfaces()) {
                    typeInstance.getSuperTypes().add(invokeType(null, intfClass, Map.of()));
                }
            }

            return typeInstance;
        }

        if (invokedType instanceof SignatureAttribute.TypeVariable typeVar) {
            if (providedArguments.size() > 0) {
                TypeInstance result = providedArguments.get(typeVar.getName());
                if (wildcard == TypeInstance.WildcardType.NONE) {
                    return result;
                }

                return result.withWildcard(wildcard);
            }

            return findTypeParameter(
                invokingClass, typeVar.getName(), classTypeParams, methodTypeParams, invocationChain);
        }

        throw new IllegalArgumentException();
    }

    private List<TypeInstance> invokeTypeArguments(
            CtClass invokingClass,
            SignatureAttribute.ClassType classType,
            SignatureAttribute.TypeParameter[] classTypeParams,
            SignatureAttribute.TypeParameter[] methodTypeParams,
            List<TypeInstance> invocationChain)
                throws NotFoundException, BadBytecode {
        List<TypeInstance> arguments = null;

        if (classType.getTypeArguments() == null) {
            return Collections.emptyList();
        }

        for (SignatureAttribute.TypeArgument typeArg : classType.getTypeArguments()) {
            if (arguments == null) {
                arguments = new ArrayList<>(2);
            }

            if (typeArg.getType() == null) {
                arguments.add(
                    getTypeInstance(Classes.ObjectsType())
                        .withWildcard(TypeInstance.WildcardType.of(typeArg.getKind())));
            }

            if (typeArg.getType() instanceof SignatureAttribute.ClassType classTypeArg) {
                CtClass argClass = resolveClass(typeArg.getType().jvmTypeName());
                TypeInstance existingInstance = null;

                List<TypeInstance> typeArgs = invokeTypeArguments(
                    argClass, classTypeArg, classTypeParams, methodTypeParams, invocationChain);

                for (int i = invocationChain.size() - 1; i >= 0; --i) {
                    TypeInstance instance = invocationChain.get(i);

                    if (TypeHelper.equals(instance.jvmType(), argClass)
                            && instance.isRaw() == typeArgs.stream().anyMatch(TypeInstance::isRaw)
                            && instance.getArguments().equals(typeArgs)) {
                        existingInstance = invocationChain.get(i);
                        break;
                    }
                }

                if (existingInstance == null) {
                    existingInstance = invokeType(
                        invokingClass, typeArg.getType(), TypeInstance.WildcardType.of(typeArg.getKind()),
                        classTypeParams, methodTypeParams, invocationChain, Map.of());
                }

                if (existingInstance != null) {
                    arguments.add(existingInstance);
                }
            }

            if (typeArg.getType() instanceof SignatureAttribute.TypeVariable typeVarArg) {
                TypeInstance typeParam = findTypeParameter(
                    invokingClass, typeVarArg.getName(), classTypeParams, methodTypeParams, invocationChain);

                if (typeParam != null) {
                    var wildcard = TypeInstance.WildcardType.of(typeArg.getKind());
                    if (wildcard != TypeInstance.WildcardType.NONE) {
                        typeParam = typeParam.withWildcard(wildcard);
                    }

                    arguments.add(typeParam);
                }
            }
        }

        return arguments != null ? arguments : Collections.emptyList();
    }

    private @Nullable TypeInstance findTypeParameter(
            CtClass invokingClass,
            String typeVariableName,
            SignatureAttribute.TypeParameter[] classTypeParams,
            SignatureAttribute.TypeParameter[] methodTypeParams,
            List<TypeInstance> invocationChain)
                throws NotFoundException, BadBytecode {
        for (SignatureAttribute.TypeParameter typeParam : methodTypeParams) {
            if (!typeParam.getName().equals(typeVariableName)) {
                continue;
            }

            return typeParam.getClassBound() != null ?
                invokeType(
                    invokingClass, typeParam.getClassBound(), TypeInstance.WildcardType.NONE, classTypeParams,
                    methodTypeParams, invocationChain, Map.of()) :
                new TypeInstance(
                    resolveClass(typeParam.getInterfaceBound()[0].jvmTypeName()),
                    List.of(), List.of(), TypeInstance.WildcardType.NONE);
        }

        for (int i = 0; i < classTypeParams.length; ++i) {
            SignatureAttribute.TypeParameter typeParam = classTypeParams[i];
            if (!typeParam.getName().equals(typeVariableName)) {
                continue;
            }

            if (!invocationChain.isEmpty()) {
                TypeInstance invoker = findInvoker(invokingClass, invocationChain.get(invocationChain.size() - 1));
                if (invoker == null || invoker.getArguments().isEmpty()) {
                    continue;
                }

                return invoker.getArguments().get(i);
            }

            return typeParam.getClassBound() != null ?
                invokeType(
                    invokingClass, typeParam.getClassBound(), TypeInstance.WildcardType.NONE, classTypeParams,
                    methodTypeParams, invocationChain, Map.of()) :
                new TypeInstance(
                    resolveClass(typeParam.getInterfaceBound()[0].jvmTypeName()),
                    List.of(), List.of(), TypeInstance.WildcardType.NONE);
        }

        return null;
    }

    private @Nullable TypeInstance findInvoker(CtClass invokingClass, TypeInstance potentialInvoker) {
        if (TypeHelper.equals(potentialInvoker.jvmType(), invokingClass)) {
            return potentialInvoker;
        }

        for (TypeInstance superType : potentialInvoker.getSuperTypes()) {
            potentialInvoker = findInvoker(invokingClass, superType);
            if (potentialInvoker != null) {
                return potentialInvoker;
            }
        }

        return null;
    }

    private CtMethod findMethod(CtClass type, Function<CtMethod, Boolean> consumer) {
        for (CtMethod method : type.getDeclaredMethods()) {
            if (!isSynthetic(method) && consumer.apply(method)) {
                return method;
            }
        }

        try {
            for (CtClass intf : type.getInterfaces()) {
                CtMethod method = findMethod(intf, consumer);
                if (method != null) {
                    return method;
                }
            }
        } catch (NotFoundException ignored) {
        }

        try {
            type = type.getSuperclass();
            if (type != null) {
                CtMethod method = findMethod(type, consumer);
                if (method != null) {
                    return method;
                }
            }
        } catch (NotFoundException ignored) {
        }

        return null;
    }

    private static boolean isSynthetic(CtMethod method) {
        return (method.getModifiers() & AccessFlag.SYNTHETIC) != 0
            || method.getMethodInfo2().getAttribute(SyntheticAttribute.tag) != null;
    }

    private static final SignatureAttribute.TypeParameter[] EMPTY_TYPE_PARAMS =
            new SignatureAttribute.TypeParameter[0];

    private static final Object NULL_ENTRY = new Object() {
        @Override
        public String toString() {
            return "<null>";
        }
    };

    private record CacheEntry(Object value, boolean found) {}

    private static class Cache {
        private final Map<Object, Object> cache = new HashMap<>();

        public CacheEntry get(Object key) {
            Object value = cache.get(key);
            if (value == NULL_ENTRY) {
                return new CacheEntry(null, true);
            }

            return new CacheEntry(value, value != null);
        }

        public <T> T put(Object key, T value) {
            cache.put(key, value == null ? NULL_ENTRY : value);
            return value;
        }
    }

    private static final class CacheKey {
        final Object item1;
        final Object item2;
        final Object item3;
        final Object item4;
        final Object item5;

        CacheKey(Object item1, Object item2) {
            this.item1 = item1;
            this.item2 = item2;
            this.item3 = null;
            this.item4 = null;
            this.item5 = null;
        }

        CacheKey(Object item1, Object item2, Object item3) {
            this.item1 = item1;
            this.item2 = item2;
            this.item3 = item3;
            this.item4 = null;
            this.item5 = null;
        }

        CacheKey(Object item1, Object item2, Object item3, Object item4) {
            this.item1 = item1;
            this.item2 = item2;
            this.item3 = item3;
            this.item4 = item4;
            this.item5 = null;
        }

        CacheKey(Object item1, Object item2, Object item3, Object item4, Object item5) {
            this.item1 = item1;
            this.item2 = item2;
            this.item3 = item3;
            this.item4 = item4;
            this.item5 = item5;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey tuple = (CacheKey)o;
            return equals(item1, tuple.item1) &&
                equals(item2, tuple.item2) &&
                equals(item3, tuple.item3) &&
                equals(item4, tuple.item4) &&
                equals(item5, tuple.item5);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + hashCode(item1);
            result = 31 * result + hashCode(item2);
            result = 31 * result + hashCode(item3);
            result = 31 * result + hashCode(item4);
            result = 31 * result + hashCode(item5);
            return result;
        }

        private static boolean equals(Object item1, Object item2) {
            if (item1 instanceof CtClass c1 && item2 instanceof CtClass c2) {
                return TypeHelper.equals(c1, c2);
            }

            if (item1 instanceof CtMethod m1 && item2 instanceof CtMethod m2) {
                return TypeHelper.equals(m1, m2);
            }

            return Objects.equals(item1, item2);
        }

        private static int hashCode(Object o) {
            if (o instanceof CtClass c) {
                return TypeHelper.hashCode(c);
            }

            if (o instanceof CtMethod m) {
                return TypeHelper.hashCode(m);
            }

            return o != null ? o.hashCode() : 0;
        }
    }

    private static Cache getCache() {
        return (Cache)CompilationContext.getCurrent().computeIfAbsent(Resolver.class, key -> new Cache());
    }

}
