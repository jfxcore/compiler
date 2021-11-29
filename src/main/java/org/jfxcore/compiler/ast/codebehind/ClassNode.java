// Copyright (c) 2021, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.compiler.ast.codebehind;

import javassist.Modifier;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.compiler.diagnostic.SourceInfo;
import org.jfxcore.compiler.ast.ObjectNode;
import org.jfxcore.compiler.ast.PropertyNode;
import org.jfxcore.compiler.ast.TypeNode;
import java.util.Collection;
import java.util.Collections;

public class ClassNode extends ObjectNode implements JavaEmitterNode {

    private final String packageName;
    private final String className;
    private final String mangledClassName;
    private final String[] parameters;
    private final int classModifiers;
    private final boolean hasCodeBehind;

    public ClassNode(
            @Nullable String packageName,
            String className,
            int classModifiers,
            String[] parameters,
            boolean hasCodeBehind,
            TypeNode type,
            Collection<PropertyNode> properties,
            SourceInfo sourceInfo) {
        super(type, properties, Collections.emptyList(), sourceInfo);
        this.packageName = packageName;
        this.className = checkNotNull(className);
        this.mangledClassName = "$markup$" + className;
        this.classModifiers = classModifiers;
        this.parameters = parameters;
        this.hasCodeBehind = hasCodeBehind;
    }

    public @Nullable String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public String getMangledClassName() {
        return mangledClassName;
    }

    public int getClassModifiers() {
        return classModifiers;
    }

    public String[] getParameters() {
        return parameters;
    }

    public boolean hasCodeBehind() {
        return hasCodeBehind;
    }

    @Override
    public void emit(JavaEmitContext context) {
        String modifiers;

        if (hasCodeBehind) {
            modifiers = "abstract ";
        } else if (Modifier.isPublic(classModifiers)) {
            modifiers = "public ";
        } else if (Modifier.isProtected(classModifiers)) {
            modifiers = "protected ";
        } else {
            modifiers = "";
        }

        StringBuilder code = context.getOutput();
        String className = hasCodeBehind ? mangledClassName : this.className;

        code.append(String.format("%sclass %s extends %s {\r\n", modifiers, className, getType().getMarkupName()));

        for (PropertyNode propertyNode : getProperties()) {
            context.emit(propertyNode);
        }

        if (parameters.length > 0 || !hasCodeBehind) {
            if (!hasCodeBehind) {
                code.append(String.format(
                    "\t/** Initializes a new instance of the {@link %s} class. */\r\n",
                    (packageName != null ? packageName + "." : "") + className));
            }

            code.append(String.format("\tpublic %s(", className));

            for (int i = 0; i < parameters.length; ++i) {
                if (i > 0) {
                    code.append(", ");
                }

                code.append(String.format("%s arg%s", parameters[i], i));
            }

            code.append(") {\r\n");

            if (parameters.length > 0) {
                code.append("\t\tsuper(");

                for (int i = 0; i < parameters.length; ++i) {
                    if (i > 0) {
                        code.append(", ");
                    }

                    code.append(String.format("arg%s", i));
                }

                code.append(");\r\n");
            }

            if (!hasCodeBehind) {
                code.append("\t\tinitializeComponent();\r\n");
            }

            code.append("\t}\r\n");
        }

        if (hasCodeBehind) {
            code.append("\t/** Loads and initializes the scene graph of this component. */\r\n");
            code.append("\tprotected final void initializeComponent() {}\r\n");
        } else {
            code.append("\tprivate void initializeComponent() {}\r\n");
        }

        code.append("}");
    }

    @Override
    public ClassNode deepClone() {
        return new ClassNode(
            packageName, className, classModifiers, parameters, hasCodeBehind, getType(),
            getProperties(), getSourceInfo());
    }

}
