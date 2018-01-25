package com.leokongwq.maven.plugins.checker;

import static org.apache.maven.plugins.annotations.LifecyclePhase.VERIFY;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author : kongwenqiang
 * DateTime: 2018/1/24 下午3:12
 * Mail:leokongwq@gmail.com
 * Description: desc
 */
@Mojo(name = "dup", defaultPhase = VERIFY)
public class DupCheckerMojo extends AbstractMojo {

    /**
     * The current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The directory containing compiled classes.
     */
    @Parameter( defaultValue = "${project.build.outputDirectory}", required = true, readonly = true )
    private File classesDirectory;

    /**
     * The directory where the webapp is built.
     */
    @Parameter( defaultValue = "${project.build.directory}/${project.build.finalName}", required = true )
    private File webappDirectory;

    @Parameter( defaultValue = "false" )
    private boolean useBaseVersion;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * You can skip the execution of the plugin if you need to.
     * But we strong recommend check the duplicate dependency or classes
     */
    @Parameter( property = "maven.dup.skip", defaultValue = "false" )
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Duplicate check.");
            return;
        }
        try {
            checkDepDependency();
            checkDupClass();
        } catch (Exception e) {
            throw new MojoExecutionException("Error during duplicate dependency or classes check.", e);
        }
    }

    /**
     * check duplicated Dependency
     */
    private void checkDepDependency() {
        List<Dependency> dependencies = getDependencies();

        Map<String, List<Dependency>> dependencyMap = new HashMap<>(200);
        for (Dependency dependency : dependencies) {
            final String artifactId = dependency.getArtifactId();
            if (dependencyMap.containsKey(artifactId)) {
                dependencyMap.get(artifactId).add(dependency);
            } else {
                List<Dependency> dupDependency = new ArrayList<>(10);
                dupDependency.add(dependency);
                dependencyMap.put(artifactId, dupDependency);
            }
        }
        getLog().info("********************[duplicated artifact start]*******************");
        for (Map.Entry<String, List<Dependency>> entry : dependencyMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                dependencyMap.get(entry.getKey()).forEach(this::printDupDependencyInfo);
            }
        }
        getLog().info("********************[duplicated artifact end]*******************");
    }

    private void printDupDependencyInfo(Dependency dependency) {
        getLog().info(dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getVersion());
    }

    private List<Dependency> getDependencies() {
        List<Dependency> dependencies = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            // don't include pom type dependencies in dependency reduced pom
            if ("pom".equals(artifact.getType())) {
                continue;
            }
            // promote
            Dependency dep = createDependency(artifact);
        }
        return dependencies;
    }

    private Dependency createDependency(Artifact artifact) {
        Dependency dep = new Dependency();
        dep.setArtifactId(artifact.getArtifactId());
        if (artifact.hasClassifier()) {
            dep.setClassifier(artifact.getClassifier());
        }
        dep.setGroupId(artifact.getGroupId());
        dep.setOptional(artifact.isOptional());
        dep.setScope(artifact.getScope());
        dep.setType(artifact.getType());
        if (useBaseVersion) {
            dep.setVersion(artifact.getBaseVersion());
        } else {
            dep.setVersion(artifact.getVersion());
        }
        return dep;
    }

    /**
     * check duplicated class
     */
    private void checkDupClass() {
        getLog().info(webappDirectory.getAbsolutePath());

        Path libPath = Paths.get(webappDirectory.getAbsolutePath(), "WEB-INF", "lib");

        if (! libPath.toFile().exists()) {
            return;
        }
        File[] libs = libPath.toFile().listFiles();
        if (libs == null || libs.length == 0) {
            return;
        }
        final Map<String, Set<String>> dupClassMap = new HashMap<>(30000);
        Arrays.stream(libs).map(this::countJar).forEach(countingResult -> {
            for (Map.Entry<String, List<String>> entry : countingResult.entrySet()) {
                entry.getValue().forEach(fullClassName -> {
                    if (! dupClassMap.containsKey(fullClassName)) {
                        Set<String> dupJarNames = new HashSet<>();
                        dupJarNames.add(entry.getKey());
                        dupClassMap.put(fullClassName, dupJarNames);
                    } else {
                        dupClassMap.get(fullClassName).add(entry.getKey());
                    }
                });
            }
        });

        int totalDupClass = 0;
        final Set<String> dupJars = new HashSet<>(dupClassMap.size() * 2);

        for (Map.Entry<String, Set<String>> entry : dupClassMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                totalDupClass++;
                dupJars.addAll(entry.getValue());
                getLog().info("*********************************************");
                getLog().error("Found duplicated class : [" + entry.getKey() + "]");
                entry.getValue().forEach(jarFileName -> getLog().info(jarFileName));
                getLog().info("*********************************************");
                getLog().info("");
            }
        }
        getLog().info("");
        getLog().info("Total Found [" + totalDupClass + "] duplicate class in [" + dupJars.size() + "] jars!");
        getLog().info("");
    }

    private Map<String, List<String>> countJar(File libFile) {
        final Map<String, List<String>> countResult = new HashMap<>(100);
        final List<String> fullClassNames = new LinkedList<>();
        countResult.put(libFile.getName(), fullClassNames);
        try {
            JarFile jar = new JarFile(libFile);
            Enumeration<JarEntry> enumFiles = jar.entries();
            while(enumFiles.hasMoreElements()) {
                JarEntry entry = enumFiles.nextElement();
                if (entry.getName().indexOf("class") > 0) {
                    fullClassNames.add(entry.getName());
                }
            }
        } catch (IOException e) {
            getLog().error(e);
        }
        return countResult;
    }
}
