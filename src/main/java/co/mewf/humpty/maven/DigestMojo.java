package co.mewf.humpty.maven;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.webjars.WebJarAssetLocator;

import co.mewf.humpty.Pipeline;
import co.mewf.humpty.config.Configuration;
import co.mewf.humpty.config.HumptyBootstrap;
import co.mewf.humpty.tools.Digester;


@Mojo(name = "digest", requiresProject = true, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class DigestMojo extends AbstractMojo {

  @Component
  private MavenProject project;
  
  @Parameter(property = "configFile", defaultValue = "")
  private String configDir;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<URL> allUrls = new ArrayList<>();
    Set<String> assetPaths = new HashSet<>();

    project.getCompileSourceRoots().forEach(sourceRoot -> {
      try {
        allUrls.add(new File(sourceRoot).toURI().toURL());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    project.getResources().stream()
      .map(Resource::getDirectory)
      .map(Paths::get)
      .map(path -> {
        try {
          return Files.walk(path);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      })
      .flatMap(s -> s)
      .forEach(path -> {
        try {
          allUrls.add(path.toFile().toURI().toURL());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    
    Path configPath = Utils.getConfigPath(project).orElseThrow(() -> new IllegalStateException("No humpty.toml file found!"));
    
    Configuration configuration = Configuration.load(configPath);
    Configuration.GlobalOptions globalOptions = configuration.getGlobalOptions();
    Path buildDir = globalOptions.getBuildDir();

    try {
      Path assetsDir = project.getResources()
        .stream()
        .map(Resource::getDirectory)
        .map(dir -> dir + "/" + globalOptions.getAssetsDir())
        .map(File::new)
        .filter(File::exists)
        .findFirst()
        .map(File::toPath)
        .orElseThrow(() -> new RuntimeException("Assets dir not found on classpath: " + globalOptions.getAssetsDir()));
      
      try (Stream<Path> paths = Files.walk(assetsDir)) {
        paths
          .filter(path -> path.toFile().isFile())
          .map(assetsDir.getParent()::relativize)
          .map(path -> {
            try {
              assetPaths.add(path.toString());
              return path.toUri().toURL();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          })
          .forEach(allUrls::add);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
      
    try {
      project.getRuntimeClasspathElements().forEach(element -> {
        try {
          URL url = new File(element).toURI().toURL();
          allUrls.add(url);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    } catch (DependencyResolutionRequiredException e) {
      throw new RuntimeException(e);
    }

    // Parent-last classloader
    ClassLoader urlClassLoader = Utils.createParentLastClassloader(allUrls, getClass().getClassLoader());
    
    Thread.currentThread().setContextClassLoader(urlClassLoader);

    assetPaths.addAll(WebJarAssetLocator.getFullPathIndex(Pattern.compile(".*"), urlClassLoader).values());
    
    Pipeline pipeline = new HumptyBootstrap(configuration, new WebJarAssetLocator(assetPaths)).createPipeline();
    
    String digestString = new Digester().processBundles(pipeline, configuration.getBundles(), buildDir)
      .entrySet()
      .stream()
      .map(entry -> "\"" + entry.getKey() + "\" = \"" + entry.getValue() + "\"")
      .collect(Collectors.joining("\n"));
    
    try {
      Files.write(Paths.get(project.getResources().get(0).getDirectory()).resolve(globalOptions.getDigestFile()), digestString.getBytes(UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
