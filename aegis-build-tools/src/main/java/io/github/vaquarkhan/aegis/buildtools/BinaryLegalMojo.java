/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.buildtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

@Mojo(
    name = "bundle-legal",
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true)
public final class BinaryLegalMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", required = true)
  private File root;

  @Parameter(defaultValue = "${project.build.directory}/binary-legal", required = true)
  private File output;

  @Parameter(defaultValue = "false")
  private boolean verify;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      Xpp3Dom shade = shadeConfiguration();
      List<BinaryLegal.Component> components = new ArrayList<>();
      for (Artifact artifact : project.getArtifacts()) {
        if (!List.of("compile", "runtime").contains(artifact.getScope())) continue;
        String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
        if (!selected(shade.getChild("artifactSet"), ga)) continue;
        if (artifact.getGroupId().equals("io.github.vaquarkhan.aegis")) continue;
        String coordinate =
            ga
                + ":"
                + artifact.getVersion()
                + (artifact.hasClassifier() ? ":" + artifact.getClassifier() : "");
        if (!"jar".equals(artifact.getType()) || artifact.getFile() == null) {
          throw new IOException("Unsupported bundled artifact: " + coordinate);
        }
        components.add(new BinaryLegal.Component(coordinate, artifact.getFile().toPath()));
      }
      BinaryLegal.Documents documents =
          BinaryLegal.assemble(findLegalRoot(root.toPath()), components);
      if (verify) {
        Xpp3Dom classifier = shade.getChild("shadedClassifierName");
        if (classifier == null) throw new IOException("Missing shaded classifier");
        var archive =
            Path.of(project.getBuild().getDirectory())
                .resolve(project.getBuild().getFinalName() + "-" + classifier.getValue() + ".jar");
        BinaryLegal.verify(archive, documents, components);
        getLog()
            .info(
                "Verified binary LICENSE/NOTICE and "
                    + components.size()
                    + " bundled third-party artifacts in "
                    + archive.getFileName());
      } else {
        Files.createDirectories(output.toPath());
        Files.writeString(output.toPath().resolve("LICENSE"), documents.license());
        Files.writeString(output.toPath().resolve("NOTICE"), documents.notice());
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Binary legal metadata: " + e.getMessage(), e);
    }
  }

  static Path findLegalRoot(Path start) throws IOException {
    Path directory = start.toAbsolutePath().normalize();
    for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
      if (Files.isRegularFile(
          candidate.resolve("tools").resolve("binary-legal").resolve("components.txt"))) {
        return candidate;
      }
    }
    throw new IOException(
        "Cannot find tools/binary-legal/components.txt in " + directory + " or its ancestors");
  }

  private Xpp3Dom shadeConfiguration() throws IOException {
    for (Plugin plugin : project.getBuildPlugins()) {
      if (!"org.apache.maven.plugins:maven-shade-plugin".equals(plugin.getKey())) continue;
      for (var execution : plugin.getExecutions()) {
        if (!execution.getGoals().contains("shade")) continue;
        if (!(execution.getConfiguration() instanceof Xpp3Dom config)) {
          throw new IOException("Missing shade execution configuration");
        }
        if (config.getChild("relocations") != null
            || config.getChild("minimizeJar") != null
            || config.getChild("extraArtifacts") != null
            || config.getChild("extraJars") != null) {
          throw new IOException(
              "Review binary legal inventory before changing Shade inputs or relocation");
        }
        return config;
      }
    }
    throw new IOException("No shade execution found");
  }

  static boolean selected(Xpp3Dom artifactSet, String ga) throws IOException {
    if (artifactSet == null) return true;
    Xpp3Dom includes = artifactSet.getChild("includes");
    Xpp3Dom excludes = artifactSet.getChild("excludes");
    return (includes == null || matches(includes, ga))
        && (excludes == null || !matches(excludes, ga));
  }

  private static boolean matches(Xpp3Dom patterns, String ga) throws IOException {
    for (Xpp3Dom child : patterns.getChildren()) {
      String pattern = child.getValue();
      // Current bundles use exact group:artifact coordinates. Do not guess future Shade selector
      // semantics.
      if (pattern == null || !pattern.matches("[\\w.-]+:[\\w.-]+")) {
        throw new IOException("Review unsupported shade artifact selector: " + pattern);
      }
      if (pattern.equals(ga)) return true;
    }
    return false;
  }
}
