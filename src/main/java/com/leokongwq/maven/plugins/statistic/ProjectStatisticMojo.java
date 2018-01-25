package com.leokongwq.maven.plugins.statistic;

import static org.apache.maven.plugins.annotations.LifecyclePhase.PROCESS_SOURCES;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author : kongwenqiang
 * DateTime: 2018/1/24 上午10:00
 * Mail:leokongwq@gmail.com
 * Description: desc
 *
 */
@Mojo(name = "statis", defaultPhase = PROCESS_SOURCES)
public class ProjectStatisticMojo extends AbstractMojo {

    private static final String[] INCLUDES_DEFAULT = {"java", "xml", "sql", "properties"};
    private static final String DOT = ".";

    /**
     * The current Maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    @Parameter(defaultValue = "${project.basedir}")
    private File basedir;

    @Parameter(defaultValue = "${project.build.outputDirectory}" )
    private File buildDirectory;

    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.testSourceDirectory}")
    private File testSourceDirectory;

    /**
     * The list of resources we want to transfer.
     */
    @Parameter( defaultValue = "${project.resources}", readonly = true )
    private List<Resource> resources;

    /**
     * The list of test resources we want to transfer.
     */
    @Parameter(defaultValue = "${project.testResources}")
    private List<Resource> testResources;

    @Parameter
    private String[] includes;

    private Set<String> suffixSets = new HashSet<>();

    private long realTotal;
    private long fakeTotal;

    @Override
    public void execute() throws MojoExecutionException {
        init();
        try {
            countDir(sourceDirectory);
            countDir(testSourceDirectory);

            for (Resource res : resources) {
                countDir(new File(res.getDirectory()));
            }
            for (Resource res : testResources) {
                countDir(new File(res.getDirectory()));
            }

            getLog().info("TOTAL LINES:" + fakeTotal + " (" + realTotal + ")");

        } catch (IOException e) {
            throw new MojoExecutionException("Unable to count lines of code", e);
        }
    }

    private void init() throws MojoExecutionException{
        if (includes == null || includes.length == 0) {
            includes = INCLUDES_DEFAULT;
        }
        Arrays.stream(includes).forEach(suffix -> suffixSets.add(suffix));
    }

    private void countDir(File dir) throws IOException {
        if(! dir.exists()){
            return;
        }
        final List<File> collected = new ArrayList<>();
        collectFiles(collected, dir);

        int realLine = 0;
        int fakeLine = 0;
        for(File file : collected){
            int[] line =  countLine(file);
            realLine += line[0];
            fakeLine += line[1];
        }

        String path = dir.getAbsolutePath().substring(basedir.getAbsolutePath().length());
        StringBuilder info = new StringBuilder().append(path).append(" : ").append(fakeLine).append(" ("+realLine+")")
            .append(" lines of code in ").append(collected.size()).append(" files");
        getLog().info(info.toString());

    }

    private void collectFiles(final List<File> collected, final File file) throws IOException {
        if (null == file) {
            return;
        }
        if (file.isFile() && isFileTypeIncluded(file)) {
            collected.add(file);
        }
        if (file.isDirectory() && null != file.listFiles()){
            for (File files : file.listFiles()) {
                collectFiles(collected, files);
            }
        }
    }

    /**
     * judge the string is the code line or not
     * @param line the file content line
     * @return boolean return true if this line is not real code line
     */
    private boolean ifFakeLine(String line) {
        if (null == line) {
            return true;
        }
        line = line.trim();

        if (line.length() == 0) {
            return true;
        }

        if (line.startsWith("import") || line.startsWith("/") || line.startsWith("*") || line.startsWith("}")) {
            return true;
        }

        return false;
    }

    private int[] countLine(File file) throws IOException {
        int realLines = 0;
        int fakeLine = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.ready()) {
                String line = reader.readLine();
                if (ifFakeLine(line)) {
                    fakeLine++;
                } else {
                    realLines++;
                }
            }
        }

        realTotal += realLines;

        String info = file.getName() + "  : " + fakeLine + " (" + realLines + ")" + " lines";
        getLog().debug(info);

        return new int[]{realLines, fakeLine};
    }

    /**
     *
     * @param file the project file e.g. *.java, *.xml
     * @return boolean return true if the file's suffix in the {{@link #includes}}
     */
    private boolean isFileTypeIncluded(File file){
        if (file.isDirectory()) {
            return false;
        }
        String fileType = getFileSuffix(file);
        return fileType != null && suffixSets.contains(fileType.toLowerCase());
    }

    /**
     * get the file's suffix name
     * @param file the project file e.g. *.java, *.xml
     * @return String return the file's suffix name
     */
    private String getFileSuffix(File file){
        String fileName = file.getName();
        int index = fileName.lastIndexOf(DOT);
        if (index > 0) {
            return fileName.substring(index + 1).toLowerCase();
        }
        return null;
    }
}