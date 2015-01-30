package co.mewf.humpty.maven;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.webjars.WebJarAssetLocator;

import co.mewf.humpty.Pipeline;
import co.mewf.humpty.config.Configuration;
import co.mewf.humpty.config.HumptyBootstrap;
import co.mewf.humpty.watch.AssetWatcher;


@Mojo(name = "watch", requiresProject = true, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class WatchMojo extends AbstractMojo {

  @Component
  private MavenProject project;
  
  @Parameter(property = "assetDirs", alias = "assetDirs", defaultValue = "src/main/resources/META-INF/resources/webjars")
  private String assetDirs;
  
  @Parameter(property = "outputDir", defaultValue = "src/main/resources/META-INF/resources/webjars")
  private String outputDir;
  
  @Parameter(property = "configFile", defaultValue = "humpty.toml")
  private String config;

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

    SortedMap<String, String> webjarIndex = new TreeMap<>();
    List<Path> assetDirPaths = Arrays.stream(assetDirs.split(",")).map(String::trim).map(Paths::get).collect(toList());

    assetDirPaths.stream().map(assetDir -> {
        try {
          return Files.walk(assetDir).map(assetDir::relativize);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      })
      .flatMap(s -> s)
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
    
    Path outputPath = Paths.get(outputDir);
    Path metaInfPath = outputPath.getName(0);
    for (int i = 1; !metaInfPath.getFileName().toString().equals("META-INF"); i++) {
      metaInfPath = metaInfPath.resolve(outputPath.getName(i));
    }
    Path metaInfParent = metaInfPath.getParent();
    
    SortedMap<String, String> defaultWebjarIndex = WebJarAssetLocator.getFullPathIndex(Pattern.compile(".*"), urlClassLoader).entrySet().stream().filter(e -> {
      return !metaInfParent.resolve(e.getValue()).toFile().exists();
    }).collect(Collectors.toMap(e -> (String) e.getKey(), e -> (String) e.getValue(), (u,v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); }, TreeMap::new));
    webjarIndex.putAll(defaultWebjarIndex);
    WebJarAssetLocator locator = new WebJarAssetLocator(webjarIndex);

    Thread.currentThread().setContextClassLoader(urlClassLoader);

    Configuration configuration = Configuration.load(config);
    Configuration.Options webjarsOptions = configuration.getOptionsFor(() -> "webjars");
    
    Pipeline pipeline = new HumptyBootstrap(configuration, locator).createPipeline();

    Appendable appendableLog = new Appendable() {
      private final Log log = new SystemStreamLog();
      @Override
      public Appendable append(CharSequence csq, int start, int end) throws IOException {
        if (csq.toString().endsWith("\n")) {
          csq = csq.subSequence(0, csq.length() - 1);
        }
        log.info(csq);
        return this;
      }

      @Override
      public Appendable append(char c) throws IOException {
        log.info(Character.toString(c));
        return this;
      }

      @Override
      public Appendable append(CharSequence csq) throws IOException {
        if (csq.toString().endsWith("\n")) {
          csq = csq.subSequence(0, csq.length() - 1);
        }
        log.info(csq);
        return this;
      }
    };
    
    getLog().info("assetDirs: " + assetDirs);
    getLog().info("outputDir: " + outputDir);
    new AssetWatcher(pipeline, assetDirPaths, Paths.get(outputDir), Paths.get(config), appendableLog).start();
  }
}
