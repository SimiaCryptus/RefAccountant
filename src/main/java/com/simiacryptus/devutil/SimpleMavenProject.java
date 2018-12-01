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

import org.apache.commons.io.FileUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.project.*;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositoryCache;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.DefaultRepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ResolutionErrorPolicy;
import org.eclipse.aether.util.repository.SimpleResolutionErrorPolicy;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

/**
 * Provides a simple api for accessing the document object model of a Maven Java project
 */
public class SimpleMavenProject {
  private static final File repositoryLocation = new File(System.getProperty("user.home"), ".m2/repository");
  private static final Logger logger = LoggerFactory.getLogger(SimpleMavenProject.class);
  /**
   * The initialized Maven component container - Plexus Dependency Injection object
   */
  public final DefaultPlexusContainer container;
  /**
   * The initialized Maven session
   */
  public final DefaultRepositorySystemSession session;
  /**
   * The initialized Maven project object
   */
  public final MavenProject project;
  /**
   * The absolute file path of the project root as passed to the constructor
   */
  public final String projectRoot;

  /**
   * Instantiates a new Simple maven project.
   *
   * @param projectRoot the project root
   * @throws IOException              the io exception
   * @throws PlexusContainerException the plexus container exception
   * @throws ComponentLookupException the component lookup exception
   * @throws ProjectBuildingException the project building exception
   */
  public SimpleMavenProject(final String projectRoot) throws IOException, PlexusContainerException, ComponentLookupException, ProjectBuildingException {
    this.projectRoot = projectRoot;
    Map<Object, Object> configProps = new LinkedHashMap<>();
    configProps.put(ConfigurationProperties.USER_AGENT, "Maven+SimiaCryptus");
    configProps.put(ConfigurationProperties.INTERACTIVE, false);
    configProps.putAll(System.getProperties());
    this.container = getPlexusContainer(repositoryLocation);
    this.session = getSession(repositoryLocation, false, configProps, container);
    this.project = getMavenProject(container, session);
  }

  /**
   * A sample CLI application which loads a maven java project and prints out the parse tree.
   *
   * @param args the input arguments
   * @throws Exception the exception
   */
  public static void main(String[] args) throws Exception {
    String root = args.length == 0 ? "H:\\SimiaCryptus\\MindsEye" : args[0];
    SimpleMavenProject mavenProject = new SimpleMavenProject(root);
    mavenProject.resolve().getDependencies().forEach((org.eclipse.aether.graph.Dependency dependency) -> {
      logger.info(String.format("Dependency: %s (%s)", dependency.getArtifact().getFile().getAbsolutePath(), dependency));
    });
    HashMap<String, CompilationUnit> parsedFiles = mavenProject.parse();
    parsedFiles.forEach((file, ast) -> {
      logger.info("File: " + file);
      Arrays.stream(ast.getProblems()).forEach(problem -> {
        logger.warn("  ERR: " + problem.getMessage());
      });
      Arrays.stream(ast.getMessages()).forEach(problem -> {
        logger.info("  MSG: " + problem.getMessage());
      });
      ast.accept(new ASTVisitor() {
        String indent = "  ";
        Stack<ASTNode> stack = new Stack<>();

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
            logger.info(String.format("  %s%s%s = %s (%s: %s)", node.getStartPosition(), indent,
                node.getClass().getSimpleName(), name.getFullyQualifiedName(),
                null == binding ? null : binding.getClass().getSimpleName(), bindingString));
          } else {
            logger.info(String.format("  %s%s%s", node.getStartPosition(), indent, node.getClass().getSimpleName()));
          }
          stack.push(node);
        }

        @Override
        public void postVisit(final ASTNode node) {
          if (node != stack.pop()) throw new IllegalStateException();
          if (indent.length() < 2) throw new IllegalStateException();
          indent = indent.substring(2);
        }

      });

    });
  }

  /**
   * Load project hash map.
   *
   * @return the hash map
   * @throws IOException                   the io exception
   * @throws PlexusContainerException      the plexus container exception
   * @throws ComponentLookupException      the component lookup exception
   * @throws ProjectBuildingException      the project building exception
   * @throws DependencyResolutionException the dependency resolution exception
   */
  public static HashMap<String, CompilationUnit> loadProject() throws IOException, PlexusContainerException, ComponentLookupException, ProjectBuildingException, DependencyResolutionException {
    return loadProject(new File(".").getAbsolutePath());
  }

  /**
   * Load project hash map.
   *
   * @param root the root
   * @return the hash map
   * @throws IOException                   the io exception
   * @throws PlexusContainerException      the plexus container exception
   * @throws ComponentLookupException      the component lookup exception
   * @throws ProjectBuildingException      the project building exception
   * @throws DependencyResolutionException the dependency resolution exception
   */
  public static HashMap<String, CompilationUnit> loadProject(final String root) throws IOException, PlexusContainerException, ComponentLookupException, ProjectBuildingException, DependencyResolutionException {
    SimpleMavenProject mavenProject = new SimpleMavenProject(root);
    mavenProject.resolve().getDependencies().forEach((org.eclipse.aether.graph.Dependency dependency) -> {
      logger.info(String.format("Dependency: %s (%s)", dependency.getArtifact().getFile().getAbsolutePath(), dependency));
    });
    return mavenProject.parse();
  }


  /**
   * Parses all files in the project.
   *
   * @return the hash map
   * @throws ComponentLookupException      the component lookup exception
   * @throws DependencyResolutionException the dependency resolution exception
   */
  public final HashMap<String, CompilationUnit> parse() throws ComponentLookupException, DependencyResolutionException {
    final String root = projectRoot;
    ASTParser astParser = ASTParser.newParser(AST.JLS9);
    astParser.setKind(ASTParser.K_EXPRESSION);
    astParser.setResolveBindings(true);
    HashMap<String, String> compilerOptions = new HashMap<>();
    compilerOptions.put(CompilerOptions.OPTION_Source, CompilerOptions.versionFromJdkLevel(ClassFileConstants.JDK1_8));
    compilerOptions.put(CompilerOptions.OPTION_DocCommentSupport, CompilerOptions.ENABLED);
    astParser.setCompilerOptions(compilerOptions);
    String[] classpathEntries = resolve().getDependencies().stream().map(x -> x.getArtifact().getFile().getAbsolutePath()).toArray(i -> new String[i]);
    String[] sourcepathEntries = Stream.concat(
        project.getTestCompileSourceRoots().stream(),
        project.getCompileSourceRoots().stream()
    ).toArray(i -> new String[i]);
    astParser.setEnvironment(classpathEntries, sourcepathEntries, null, true);
    HashMap<String, CompilationUnit> results = new HashMap<>();
    astParser.createASTs(
        FileUtils.listFiles(new File(root), new String[]{"java"}, true).stream().map(x -> x.getAbsolutePath()).toArray(i -> new String[i]),
        null,
        new String[]{},
        new FileASTRequestor() {
          @Override
          public void acceptAST(final String source, final CompilationUnit ast) {
            results.put(source, ast);
          }
        },
        new NullProgressMonitor()
    );

    return results;
  }

  /**
   * Resolves Maven dependencies
   *
   * @return the dependency resolution result
   * @throws ComponentLookupException      the component lookup exception
   * @throws DependencyResolutionException the dependency resolution exception
   */
  public DependencyResolutionResult resolve() throws org.codehaus.plexus.component.repository.exception.ComponentLookupException, DependencyResolutionException {
    return container.lookup(ProjectDependenciesResolver.class).resolve(new DefaultDependencyResolutionRequest().setRepositorySession(session).setMavenProject(project));
  }

  private MavenProject getMavenProject(final DefaultPlexusContainer container, final DefaultRepositorySystemSession session) throws ProjectBuildingException, org.codehaus.plexus.component.repository.exception.ComponentLookupException {
    DefaultProjectBuildingRequest request = new DefaultProjectBuildingRequest();
    request.setRepositorySession(session);
    return container.lookup(ProjectBuilder.class).build(new File(projectRoot, "pom.xml"), request).getProject();
  }

  @Nonnull
  private DefaultRepositorySystemSession getSession(final File repositoryLocation, final boolean isOffline, final Map<Object, Object> configProps, final DefaultPlexusContainer container) throws org.codehaus.plexus.component.repository.exception.ComponentLookupException {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    session.setConfigProperties(configProps);
    session.setCache(new DefaultRepositoryCache());
    session.setOffline(isOffline);
    session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
    session.setResolutionErrorPolicy(new SimpleResolutionErrorPolicy(ResolutionErrorPolicy.CACHE_NOT_FOUND, ResolutionErrorPolicy.CACHE_NOT_FOUND));
    session.setArtifactTypeRegistry(RepositoryUtils.newArtifactTypeRegistry(container.lookup(ArtifactHandlerManager.class)));
    session.setLocalRepositoryManager(container.lookup(DefaultRepositorySystem.class).newLocalRepositoryManager(session, new LocalRepository(repositoryLocation)));
    return session;
  }

  @Nonnull
  private DefaultPlexusContainer getPlexusContainer(final File repositoryLocation) throws IOException, PlexusContainerException {
    DefaultRepositoryLayout defaultRepositoryLayout = new DefaultRepositoryLayout();
    ArtifactRepositoryPolicy repositoryPolicy = new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);
    String url = "file://" + repositoryLocation.getCanonicalPath();
    ArtifactRepository repository = new MavenArtifactRepository("central", url, defaultRepositoryLayout, repositoryPolicy, repositoryPolicy);
    ClassWorld classWorld = new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader());
    ContainerConfiguration configuration = new DefaultContainerConfiguration()
        .setClassWorld(classWorld).setRealm(classWorld.getClassRealm(null))
        .setClassPathScanning("index").setAutoWiring(true).setJSR250Lifecycle(true).setName("maven");
    return new DefaultPlexusContainer(configuration, new BasicModule(repository));
  }

}
