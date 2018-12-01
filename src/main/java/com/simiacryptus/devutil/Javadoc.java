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

import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.ProjectBuildingException;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.jdt.core.dom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Analyzes a project based on class member dependencies
 */
public class Javadoc {
  private static final Logger logger = LoggerFactory.getLogger(Javadoc.class);

  /**
   * Load model summary hash map.
   *
   * @return the hash map
   */
  public static HashMap<String, TreeMap<String, String>> loadModelSummary() {
    try {
      return loadModelSummary(SimpleMavenProject.loadProject());
    } catch (IOException | PlexusContainerException | DependencyResolutionException | ProjectBuildingException | ComponentLookupException e) {
      throw new RuntimeException(e);
    }

  }

  /**
   * Load model summary hash map.
   *
   * @param project the project
   * @return the hash map
   */
  @Nonnull
  public static HashMap<String, TreeMap<String, String>> loadModelSummary(final HashMap<String, CompilationUnit> project) {
    HashMap<String, TreeMap<String, String>> projectData = new HashMap<>();
    project.forEach((file, ast) -> {
      ast.accept(new ASTVisitor() {
        @Override
        public boolean visit(final TypeDeclaration node) {
          TreeMap<String, String> classData = new TreeMap<>();
          org.eclipse.jdt.core.dom.Javadoc javadoc = node.getJavadoc();
          if (null != javadoc) {
            classData.put(":class", Javadoc.toString(javadoc));
          }
          Arrays.stream(node.getFields()).forEach(declaration -> {
            org.eclipse.jdt.core.dom.Javadoc fieldJavadoc = declaration.getJavadoc();
            if (0 != (declaration.getModifiers() & Modifier.STATIC)) return;
            if (0 != (declaration.getModifiers() & Modifier.FINAL)) return;
            List<VariableDeclarationFragment> fragments = declaration.fragments();
            for (final VariableDeclarationFragment fragment : fragments) {
              String key = fragment.getName().getFullyQualifiedName();
              if (null != fieldJavadoc) classData.put(key, Javadoc.toString(fieldJavadoc));
            }
          });
          Arrays.stream(node.getMethods()).forEach(declaration -> {
            org.eclipse.jdt.core.dom.Javadoc methodJavadoc = declaration.getJavadoc();
            if (0 != (declaration.getModifiers() & Modifier.STATIC)) return;
            if (declaration.isConstructor()) return;
            String identifier = declaration.getName().getIdentifier();
            if (identifier.startsWith("set") && identifier.length() > 3 && identifier.substring(3, 4) != identifier.substring(3, 4).toLowerCase() && declaration.parameters().size() == 1) {
              identifier = identifier.substring(3, 4).toLowerCase() + identifier.substring(4);
            } else {
              return;
            }
            if (null != methodJavadoc) classData.put(identifier, Javadoc.toString(methodJavadoc));
          });
          projectData.put(node.resolveBinding().getQualifiedName(), classData);
          return super.visit(node);
        }
      });
    });
    return projectData;
  }

  /**
   * To string string.
   *
   * @param javadoc the javadoc
   * @return the string
   */
  @Nonnull
  public static String toString(final org.eclipse.jdt.core.dom.Javadoc javadoc) {
    String trim = javadoc.toString().trim();
    if (!trim.startsWith("/*")) {
      throw new IllegalArgumentException(trim);
    }
    if (!trim.endsWith("*/")) {
      throw new IllegalArgumentException(trim);
    }
    return Arrays.stream(trim.split("\n")).map(x -> {
      return x.trim().replaceAll("^/?\\** ?", "").replaceAll("\\**/$", "");
    })
        .filter(x -> !x.isEmpty())
        .filter(x -> !x.trim().startsWith("@"))
        .reduce((a, b) -> a + "\n" + b).get();
  }

}
