/*******************************************************************************
 * Copyright (c) 2019 Red Hat, Inc. and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.tycho.extras.pde;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.ResolvedArtifactKey;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.osgitools.DefaultReactorProject;
import org.eclipse.tycho.core.osgitools.OsgiBundleProject;
import org.eclipse.tycho.core.utils.TychoProjectUtils;

/**
 * Builds a .target file describing the dependencies for current project. It differs from
 * <code>maven-dependency-plugin:list</code> in the fact that it does return location to bundles,
 * and not to nested jars (in case bundle contain some).
 * 
 * @deprecated only used for API-analyis which is now better done using
 *             tycho-apitools-plugin:analyse
 */
@Mojo(name = "list-dependencies", defaultPhase = LifecyclePhase.GENERATE_TEST_RESOURCES, requiresProject = true, threadSafe = true, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME)
@Deprecated(since = "3.0.0", forRemoval = true)
public class ListDependenciesMojo extends AbstractMojo {

    @Parameter(property = "project")
    private MavenProject project;

    @Parameter(property = "skip")
    private boolean skip;

    @Component(role = TychoProject.class)
    private Map<String, TychoProject> projectTypes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipped");
            return;
        }
        File outputFile = new File(project.getBuild().getDirectory(), "dependencies-list.txt");
        try {
            outputFile.getParentFile().mkdirs();
            outputFile.createNewFile();
        } catch (IOException ex) {
            throw new MojoFailureException(ex.getMessage(), ex);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath())) {

            Set<File> dependencyPaths = collectProjectDependencyPaths(project, projectTypes);
            for (File file : dependencyPaths) {
                writeLocation(writer, file);
            }
        } catch (IOException e) {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public static Set<File> collectProjectDependencyPaths(MavenProject project,
            Map<String, TychoProject> projectTypes) {
        ReactorProject reactorProject = DefaultReactorProject.adapt(project);
        Set<File> deps = TychoProjectUtils.getDependencyArtifacts(reactorProject).getArtifacts().stream()
                .filter(desc -> !desc.getLocation(true).equals(project.getBasedir())) // remove self
                .map(d -> d.getMavenProject() == null //
                        ? d.getLocation(true)
                        : d.getMavenProject().getArtifact(d.getClassifier()))
                .collect(Collectors.toSet());

        TychoProject projectType = projectTypes.get(project.getPackaging());
        if (projectType instanceof OsgiBundleProject bundleProject) {
            Map<String, ResolvedArtifactKey> artifacts = bundleProject.getAnnotationArtifacts(reactorProject);
            if (!artifacts.isEmpty()) {
                return Stream.concat(deps.stream(), artifacts.values().stream().map(ResolvedArtifactKey::getLocation))
                        .collect(Collectors.toSet());
            }
        }
        return deps;
    }

    private void writeLocation(BufferedWriter writer, File location) throws IOException {
        if (location == null) {
            return;
        }
        String path = location.getAbsolutePath();
        writer.write(path);
        writer.write(System.lineSeparator());
    }

}
