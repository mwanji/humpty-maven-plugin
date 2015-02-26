package co.mewf.humpty.maven;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.webjars.WebJarAssetLocator;

import co.mewf.humpty.Pipeline;
import co.mewf.humpty.config.Configuration;
import co.mewf.humpty.config.HumptyBootstrap;
import co.mewf.humpty.tools.Watcher;


@Mojo(name = "watch", requiresProject = true, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class WatchMojo extends AbstractMojo {

  @Component
  private MavenProject project;
  
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<URL> allUrls = new ArrayList<>();
    Set<String> assetPaths = new HashSet<>();

    Path configPath = Utils.getConfigPath(project).orElseThrow(() -> new IllegalStateException("No humpty.toml file found!"));
    Configuration configuration = Configuration.load(configPath);

    project.getCompileSourceRoots().forEach(sourceRoot -> {
      try {
        allUrls.add(new File(sourceRoot).toURI().toURL());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    Path assetsDir = Utils.getFullAssetsDirPath(project, configuration.getGlobalOptions().getAssetsDir()).orElseThrow(() -> new RuntimeException("Assets dir not found on classpath: " + configuration.getGlobalOptions().getAssetsDir()));
    
    try (Stream<Path> paths = Files.walk(assetsDir)) {
      paths.map(assetsDir.getParent()::relativize)
        .map(path -> {
          assetPaths.add(path.toString());
          
          return path;
        })
        .map(path -> {
          try {
            return path.toUri().toURL();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        })
        .forEach(allUrls::add);
    } catch (IOException e) {
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

    ClassLoader urlClassLoader = Utils.createParentLastClassloader(allUrls, getClass().getClassLoader());
    Thread.currentThread().setContextClassLoader(urlClassLoader);
    
    assetPaths.addAll(WebJarAssetLocator.getFullPathIndex(Pattern.compile(".*"), urlClassLoader).values());
    WebJarAssetLocator locator = new WebJarAssetLocator(assetPaths);

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
    
    Map<Path, Path> cache = new HashMap<>();
    Path cacheDir;
    try {
      cacheDir = Files.createTempDirectory(null);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Path watchToml = Paths.get(project.getResources().get(0).getDirectory()).resolve(configuration.getGlobalOptions().getWatchFile());
    
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        Files.deleteIfExists(watchToml);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }));
    
    new Watcher(pipeline, assetsDir, configuration, appendableLog, (source, target) -> {
      Path outputPath = cacheDir.resolve(source);
      cache.put(source, outputPath);
      String watchTomlString = cache.entrySet().stream().map(entry -> {
        return "\"" + entry.getKey() + "\" = \"" + entry.getValue() + "\"";
      }).collect(Collectors.joining("\n", "", "\n"));
      
      try {
        Files.write(outputPath, target.getBytes(UTF_8), CREATE, WRITE, TRUNCATE_EXISTING);
        Files.write(watchToml, watchTomlString.getBytes(UTF_8), CREATE, WRITE, TRUNCATE_EXISTING);
        getLog().info(source + " -> " + outputPath);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }).start();
  }
}
