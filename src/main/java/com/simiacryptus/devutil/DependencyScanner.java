/*
 * Copyright (c) 2018 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.simiacryptus.devutil;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.Javadoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.Stack;

/**
 * Analyzes a project based on class member dependencies
 */
public class DependencyScanner {
  private static final Logger logger = LoggerFactory.getLogger(DependencyScanner.class);

  /**
   * A sample CLI application which loads a maven java project and prints out the parse tree.
   *
   * @param args the input arguments
   * @throws Exception the exception
   */
  public static void main(String[] args) throws Exception {
    String root = args.length == 0 ? "H:\\SimiaCryptus\\MindsEye" : args[0];
    SimpleMavenProject.loadProject(root).forEach((file, ast) -> {
      logger.info("File: " + file);
      logTree(ast);
    });
  }

  /**
   * Log tree.
   *
   * @param ast the ast
   */
  public static void logTree(final CompilationUnit ast) {
    Arrays.stream(ast.getProblems()).forEach(problem -> {
      logger.warn("  ERR: " + problem.getMessage());
    });
    Arrays.stream(ast.getMessages()).forEach(problem -> {
      logger.info("  MSG: " + problem.getMessage());
    });
    ast.accept(new ASTVisitor() {
      public boolean useJavaDoc = false;
      String indent = "  ";
      Stack<ASTNode> stack = new Stack<>();
      String currentCodeContext = "";

      @Override
      public void preVisit(final ASTNode node) {
        indent += "  ";
        if (node instanceof Name) {
          Name name = (Name) node;
          IBinding binding = name.resolveBinding();
          String bindingString;
          if (binding == null) {
            bindingString = "???";
          } else if (binding instanceof ITypeBinding) {
            bindingString = ((ITypeBinding) binding).getBinaryName();
          } else {
            bindingString = binding.toString();
          }
          logger.debug(String.format("  %s%s%s = %s (%s: %s)", node.getStartPosition(), indent,
              node.getClass().getSimpleName(), name.getFullyQualifiedName(),
              null == binding ? null : binding.getClass().getSimpleName(), bindingString));
        } else {
          logger.debug(String.format("  %s%s%s", node.getStartPosition(), indent, node.getClass().getSimpleName()));
        }
        stack.push(node);
      }

      @Override
      public void postVisit(final ASTNode node) {
        if (node != stack.pop()) throw new IllegalStateException();
        if (indent.length() < 2) throw new IllegalStateException();
        indent = indent.substring(2);
      }

      @Override
      public boolean visit(final SimpleName node) {
        IBinding binding = node.resolveBinding();
        if (binding instanceof IMethodBinding) {
          if (!(node.getParent() instanceof MethodDeclaration)) {
            String ref = toStringMethod(((IMethodBinding) binding));
            logger.info(String.format("   Ref %s", ref));
          }
        } else if (binding instanceof IVariableBinding) {
          IVariableBinding variableBinding = (IVariableBinding) binding;
          String ref = toStringVar(variableBinding);
          if (null != ref) logger.info(String.format("   Ref %s", ref));
        }
        return super.visit(node);
      }

      @Override
      public boolean visit(final ConstructorInvocation node) {
        IBinding binding = node.resolveConstructorBinding();
        String ref = toStringMethod(((IMethodBinding) binding));
        logger.info(String.format("   Ref %s", ref));
        return super.visit(node);
      }

      @Override
      public boolean visit(final SuperConstructorInvocation node) {
        IMethodBinding binding = node.resolveConstructorBinding();
        String ref = toStringMethod(binding);
        logger.info(String.format("   Ref %s", ref));
        return super.visit(node);
      }

      @Override
      public boolean visit(final VariableDeclarationFragment node) {
        Optional<ASTNode> fieldDeclaration = stack.stream().filter(x -> x instanceof FieldDeclaration).findAny();
        if (fieldDeclaration.isPresent()) {
          Javadoc javadoc = ((FieldDeclaration) fieldDeclaration.get()).getJavadoc();
          IVariableBinding variableBinding = node.resolveBinding();
          if (null == variableBinding) {
            logger.info(String.format("  UNRESOLVED Field %s", node));
          } else {
            ITypeBinding declaringClass = variableBinding.getDeclaringClass();
            currentCodeContext = String.format("%s::%s", null == declaringClass ? null : declaringClass.getBinaryName(), variableBinding.getName());
            logger.info(String.format("  Field %s %s", currentCodeContext,
                (!useJavaDoc || null == javadoc ? node : javadoc).toString().replaceAll("\n", "\n    ").trim()));
          }
        }
        return super.visit(node);
      }

      @Override
      public boolean visit(final MethodDeclaration node) {
        Javadoc javadoc = node.getJavadoc();
        IMethodBinding methodBinding = node.resolveBinding();
        currentCodeContext = toStringMethod(methodBinding);
        logger.info(String.format("  Method %s %s", currentCodeContext,
            (!useJavaDoc || null == javadoc ? node : javadoc).toString().replaceAll("\n", "\n    ").trim()));
        return super.visit(node);
      }

    });
  }


  private static String toStringMethod(final IMethodBinding methodBinding) {
    final String symbolStr;
    if (null != methodBinding) {
      ITypeBinding[] parameterTypes = methodBinding.getParameterTypes();
      String params = null == parameterTypes ? "null" : Arrays.stream(parameterTypes).map(x -> toStringType(x)).map(x -> null == x ? "null" : x).reduce((a, b) -> a + "," + b).orElse("");
      String name = methodBinding.getDeclaringClass().getBinaryName() + "::" + methodBinding.getName();
      symbolStr = String.format("%s(%s)", name, params);
    } else {
      symbolStr = "???";
    }
    return symbolStr;
  }


  private static String toStringType(final ITypeBinding x) {
    if (null == x) return "null";
    else if (x.isPrimitive()) return x.getName();
    else if (x.isArray()) return toStringType(x.getElementType()) + "[]";
    else return x.getBinaryName();

  }

  private static String toStringVar(final IVariableBinding iVariableBinding) {
    if (null == iVariableBinding) return null;
    ITypeBinding declaringClass = iVariableBinding.getDeclaringClass();
    if (null == declaringClass) return null;
    return declaringClass.getBinaryName() + "::" + iVariableBinding.getName();
  }

}
