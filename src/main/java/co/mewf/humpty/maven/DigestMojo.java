package co.mewf.humpty.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    project.getCompileSourceRoots().forEach(sourceRoot -> {
      try {
        allUrls.add(new File(sourceRoot).toURI().toURL());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    Path configPath = project.getResources().stream()
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
      .map(path -> {
        try {
          allUrls.add(path.toFile().toURI().toURL());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        return path;
      })
      .filter(path -> {
        return path.getFileName().toString().equals("humpty.toml");
      })
      .findFirst()
      .orElseThrow(() -> new IllegalStateException("No humpty.toml file found!"));
    
    Configuration configuration = Configuration.load(configPath);
    Configuration.GlobalOptions globalOptions = configuration.getGlobalOptions();
    Path buildDir = globalOptions.getBuildDir();

    SortedMap<String, String> webjarIndex = new TreeMap<>();

    try {
      Files.walk(globalOptions.getAssetsDir())
        .map(globalOptions.getAssetsDir()::relativize)
        .map(path -> {
          StringBuilder reverse = new StringBuilder();
          path.iterator().forEachRemaining(p -> {
            reverse.insert(0, '/');
            reverse.insert(0, p.getFileName());
          });
          webjarIndex.put(reverse.toString(), path.toString());
  
          try {
            return path.toFile().toURI().toURL();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        })
        .forEach(allUrls::add);
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
    URLClassLoader urlClassLoader = new URLClassLoader(allUrls.toArray(new URL[0]), getClass().getClassLoader()) {
      public URL getResource(String name) {
        URL url = findResource(name);
        
        if (url == null) {
          url = getParent().getResource(name);
        }
        
        return url;
      };
    };
    
    Path metaInfPath = buildDir.getName(0);
    for (int i = 1; !metaInfPath.getFileName().toString().equals("META-INF"); i++) {
      metaInfPath = metaInfPath.resolve(buildDir.getName(i));
    }
    Path metaInfParent = metaInfPath.getParent();
    
    SortedMap<String, String> defaultWebjarIndex = WebJarAssetLocator.getFullPathIndex(Pattern.compile(".*"), urlClassLoader).entrySet().stream().filter(e -> {
      return !metaInfParent.resolve(e.getValue()).toFile().exists();
    }).collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue(), (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); }, TreeMap::new));
    webjarIndex.putAll(defaultWebjarIndex);
    WebJarAssetLocator locator = new WebJarAssetLocator(webjarIndex);

    Thread.currentThread().setContextClassLoader(urlClassLoader);

    Pipeline pipeline = new HumptyBootstrap(configuration, locator).createPipeline();

    new Digester().processBundles(pipeline, configuration.getBundles(), buildDir, globalOptions.getDigestFile());
  }
}
