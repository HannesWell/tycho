## BND Workspace Layout and Pomless Builds

Tycho supports building projects that use the **BND Workspace Layout** as [described here](https://bndtools.org/concepts.html).

### BND Workspace Layout
A BND Workspace layout build layout usually has the following structure:

- `root folder` - this usually is the root of your project repository (e.g. git)
    - `cnf` - configuration folder for general setup
        - `build.bnd` - main configuration file
        - `ext` - additional configuration (optional)
    - `bundle1` - A bundle project
        - `bnd.bnd` - project configuration file
    - `bundle2` - Another bundle project
        - `bnd.bnd` - project configuration file
    - `...`

any folder that do not match the layout is ignored.

### Pomless Builds
Given the above layout, Tycho now has a good knowledge about what your build artifacts are.
In contrast to a traditional maven build where each module has to contain a `pom.xml` file Tycho can derive most all from your supplied bnd configuration files, so everything is configured there and usually no additional maven configuration is required, therefore this build is completely pomless (no pom.xml), there are only a few steps to consider:

1. Add a folder called `.mvn` to the root
2. Inside the `.mvn` folder place a file called `extensions.xml` with the following content:

```
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>org.eclipse.tycho</groupId>
    <artifactId>tycho-build</artifactId>
    <version>${tycho-version}</version>
  </extension>
</extensions>
```

3. create a file called `maven.config` in the `.mvn` folder with the following content (adjust the version accordingly!):

```
-Dtycho-version=3.0.0
```
4. You can now run your build with `mvn clean verify`

A runnable demo can be found here: https://github.com/eclipse-tycho/tycho/tree/master/demo/bnd-workspace