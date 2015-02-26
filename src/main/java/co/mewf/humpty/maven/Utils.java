package co.mewf.humpty.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

class Utils {
  
  public static Optional<Path> getConfigPath(MavenProject project) {
    return project.getResources()
      .stream()
      .map(Resource::getDirectory)
      .map(Paths::get)
      .map(path -> path.resolve("humpty.toml"))
      .filter(path -> path.toFile().exists())
      .findFirst();
  }
  
  public static Optional<Path> getFullAssetsDirPath(MavenProject project, Path assetsDir) {
    return project.getResources()
      .stream()
      .map(Resource::getDirectory)
      .map(dir -> dir + "/" + assetsDir)
      .map(File::new)
      .filter(File::exists)
      .findFirst()
      .map(File::toPath);
  }
  
  public static ClassLoader createParentLastClassloader(List<URL> allUrls, ClassLoader parent) {
    return new URLClassLoader(allUrls.toArray(new URL[0]), parent) {
      public URL getResource(String name) {
        URL url = findResource(name);
        
        if (url == null) {
          url = getParent().getResource(name);
        }
        
        return url;
      };
    };
  }

  private Utils() {}
}
