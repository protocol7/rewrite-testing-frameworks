/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class UpdateTestAnnotation extends Recipe {

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new UpdateTestAnnotationVisitor();
    }

    private static class UpdateTestAnnotationVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            doAfterVisit(new ChangeType("org.junit.Test", "org.junit.jupiter.api.Test"));
            return super.visitCompilationUnit(cu, ctx);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

            boolean changed = false;
            List<J.Annotation> annotations = new ArrayList<>(m.getAnnotations());
            for (int i = 0, annotationsSize = annotations.size(); i < annotationsSize; i++) {

                J.Annotation a = annotations.get(i);
                if (TypeUtils.isOfClassType(a.getType(), "org.junit.Test")) {
                    //If we found the annotation, we change the visibility of the method to package. Because the
                    m = m.withModifiers(ListUtils.map(m.getModifiers(), mod -> {
                        J.Modifier.Type modifierType = mod.getType();
                        return (modifierType == J.Modifier.Type.Protected || modifierType == J.Modifier.Type.Private ||
                                modifierType == J.Modifier.Type.Public) ? null : mod;
                    }));
                    m = maybeAutoFormat(method, m, ctx, getCursor().dropParentUntil(it -> it instanceof J));

                    annotations.set(i, a.withArguments(null));
                    if (a.getArguments() == null) {
                        continue;
                    }
                    List<Expression> args = a.getArguments();
                    for (Expression arg : args) {
                        if (arg instanceof J.Assignment) {
                            J.Assignment assign = (J.Assignment) arg;
                            String assignParamName = ((J.Identifier) assign.getVariable()).getSimpleName();
                            Expression e = assign.getAssignment();
                            if (m.getBody() == null) {
                                continue;
                            }
                            if (assignParamName.equals("expected")) {
                                List<Statement> statements = m.getBody().getStatements();
                                String strStatements = statements.stream().map(Statement::print)
                                        .collect(Collectors.joining(";")) + ";";
                                m = m.withTemplate(
                                        template("{ assertThrows(#{}, () -> {#{}}); }")
                                                .staticImports("org.junit.jupiter.api.Assertions.assertThrows")
                                                .build(),
                                        m.getCoordinates().replaceBody(),
                                        e,
                                        strStatements
                                );
                                maybeAddImport("org.junit.jupiter.api.Assertions", "assertThrows");
                            } else if (assignParamName.equals("timeout")) {
                                doAfterVisit(new AddTimeoutAnnotation(e));
                            }
                        }
                        changed = true;
                    }
                }
            }

            if (changed) {
                m = m.withAnnotations(annotations);
            }
            return m;
        }

        private static class AddTimeoutAnnotation extends JavaIsoVisitor<ExecutionContext> {

            private final Expression expression;

            public AddTimeoutAnnotation(Expression expression) {
                this.expression = expression;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
                method = method.withTemplate(
                        template("@Timeout(#{})")
                                .imports("org.junit.jupiter.api.Timeout")
                                .build(),
                        method.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)),
                        expression
                );
                maybeAddImport("org.junit.jupiter.api.Timeout");
                return method;
            }
        }
    }
}