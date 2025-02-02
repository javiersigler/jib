/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import com.google.cloud.tools.jib.Command;
import com.google.cloud.tools.jib.IntegrationTestingConfiguration;
import com.google.cloud.tools.jib.registry.LocalRegistry;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestException;
import java.time.Instant;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.UnexpectedBuildFailure;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Integration tests for building single project images. */
public class SingleProjectIntegrationTest {

  @ClassRule
  public static final LocalRegistry localRegistry1 =
      new LocalRegistry(5000, "testuser", "testpassword");

  @ClassRule
  public static final LocalRegistry localRegistry2 =
      new LocalRegistry(6000, "testuser2", "testpassword2");

  @ClassRule public static final TestProject simpleTestProject = new TestProject("simple");

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static boolean isJava11RuntimeOrHigher() {
    Iterable<String> split = Splitter.on(".").split(System.getProperty("java.version"));
    return Integer.valueOf(split.iterator().next()) >= 11;
  }

  private static void assertWorkingDirectory(String expected, String imageReference)
      throws IOException, InterruptedException {
    Assert.assertEquals(
        expected,
        new Command("docker", "inspect", "-f", "{{.Config.WorkingDir}}", imageReference)
            .run()
            .trim());
  }

  private static void assertEntrypoint(String expected, String imageReference)
      throws IOException, InterruptedException {
    Assert.assertEquals(
        expected,
        new Command("docker", "inspect", "-f", "{{.Config.Entrypoint}}", imageReference)
            .run()
            .trim());
  }

  private static void assertLayerSize(int expected, String imageReference)
      throws IOException, InterruptedException {
    Command command =
        new Command("docker", "inspect", "-f", "{{join .RootFS.Layers \",\"}}", imageReference);
    String layers = command.run().trim();
    Assert.assertEquals(expected, Splitter.on(",").splitToList(layers).size());
  }

  /**
   * Asserts that the test project has the required exposed ports, labels and volumes.
   *
   * @param imageReference the image to test
   * @throws IOException if the {@code docker inspect} command fails to run
   * @throws InterruptedException if the {@code docker inspect} command is interrupted
   */
  private static void assertDockerInspect(String imageReference)
      throws IOException, InterruptedException {
    String dockerInspect = new Command("docker", "inspect", imageReference).run();
    Assert.assertThat(
        dockerInspect,
        CoreMatchers.containsString(
            "            \"Volumes\": {\n"
                + "                \"/var/log\": {},\n"
                + "                \"/var/log2\": {}\n"
                + "            },"));
    Assert.assertThat(
        dockerInspect,
        CoreMatchers.containsString(
            "            \"ExposedPorts\": {\n"
                + "                \"1000/tcp\": {},\n"
                + "                \"2000/udp\": {},\n"
                + "                \"2001/udp\": {},\n"
                + "                \"2002/udp\": {},\n"
                + "                \"2003/udp\": {}"));
    Assert.assertThat(
        dockerInspect,
        CoreMatchers.containsString(
            "            \"Labels\": {\n"
                + "                \"key1\": \"value1\",\n"
                + "                \"key2\": \"value2\"\n"
                + "            }"));
  }

  private static void assertExtraDirectoryDeprecationWarning(String buildFile)
      throws DigestException, IOException, InterruptedException {
    String targetImage = "localhost:6000/simpleimage:gradle" + System.nanoTime();
    BuildResult buildResult =
        JibRunHelper.buildToDockerDaemon(simpleTestProject, targetImage, buildFile);
    Assert.assertEquals(
        "Hello, world. \n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        new Command("docker", "run", "--rm", targetImage).run());
    Assert.assertThat(
        buildResult.getOutput(),
        CoreMatchers.containsString(
            "'jib.extraDirectory', 'jib.extraDirectory.path', and 'jib.extraDirectory.permissions' "
                + "are deprecated; use 'jib.extraDirectories.paths' and "
                + "'jib.extraDirectories.permissions'"));
  }

  private static void buildAndRunComplex(
      String imageReference, String username, String password, LocalRegistry targetRegistry)
      throws IOException, InterruptedException {
    Path baseCache = simpleTestProject.getProjectRoot().resolve("build/jib-base-cache");
    BuildResult buildResult =
        simpleTestProject.build(
            "clean",
            "jib",
            "-Djib.baseImageCache=" + baseCache,
            "-Djib.console=plain",
            "-D_TARGET_IMAGE=" + imageReference,
            "-D_TARGET_USERNAME=" + username,
            "-D_TARGET_PASSWORD=" + password,
            "-DsendCredentialsOverHttp=true",
            "-b=complex-build.gradle");

    JibRunHelper.assertBuildSuccess(buildResult, "jib", "Built and pushed image as ");
    Assert.assertThat(buildResult.getOutput(), CoreMatchers.containsString(imageReference));

    targetRegistry.pull(imageReference);
    assertDockerInspect(imageReference);
    String history = new Command("docker", "history", imageReference).run();
    Assert.assertThat(history, CoreMatchers.containsString("jib-gradle-plugin"));
    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrwxr-xr-x\nrwxrwxrwx\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n"
            + "-Xms512m\n-Xdebug\nenvvalue1\nenvvalue2\n",
        new Command("docker", "run", "--rm", imageReference).run());
  }

  @Before
  public void setup() throws IOException, InterruptedException {
    // Pull distroless and push to local registry so we can test 'from' credentials
    localRegistry1.pullAndPushToLocal("gcr.io/distroless/java:latest", "distroless/java");
  }

  @Test
  public void testBuild_simple() throws IOException, InterruptedException, DigestException {
    String targetImage =
        IntegrationTestingConfiguration.getTestRepositoryLocation()
            + "/simpleimage:gradle"
            + System.nanoTime();

    // Test empty output error
    try {
      simpleTestProject.build(
          "clean",
          "jib",
          "-Djib.useOnlyProjectCache=true",
          "-Djib.console=plain",
          "-x=classes",
          "-D_TARGET_IMAGE=" + targetImage);
      Assert.fail();

    } catch (UnexpectedBuildFailure ex) {
      Assert.assertThat(
          ex.getMessage(),
          CoreMatchers.containsString(
              "No classes files were found - did you compile your project?"));
    }

    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildAndRun(simpleTestProject, targetImage));
    assertDockerInspect(targetImage);
    JibRunHelper.assertSimpleCreationTimeIsEqual(Instant.EPOCH, targetImage);
    assertWorkingDirectory("/home", targetImage);
    assertEntrypoint(
        "[java -cp /d1:/d2:/app/resources:/app/classes:/app/libs/* com.test.HelloWorld]",
        targetImage);
    assertLayerSize(8, targetImage);
  }

  @Test
  public void testBuild_dockerDaemonBase()
      throws IOException, InterruptedException, DigestException {
    String targetImage =
        IntegrationTestingConfiguration.getTestRepositoryLocation()
            + "/simplewithdockerdaemonbase:gradle"
            + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildAndRunFromLocalBase(
            targetImage, "docker://gcr.io/distroless/java:latest"));
  }

  @Test
  public void testBuild_tarBase() throws IOException, InterruptedException, DigestException {
    Path path = temporaryFolder.getRoot().toPath().resolve("docker-save-distroless");
    new Command("docker", "save", "gcr.io/distroless/java:latest", "-o", path.toString()).run();
    String targetImage =
        IntegrationTestingConfiguration.getTestRepositoryLocation()
            + "/simplewithtarbase:gradle"
            + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildAndRunFromLocalBase(targetImage, "tar://" + path));
  }

  @Test
  public void testBuild_failOffline() {
    String targetImage =
        IntegrationTestingConfiguration.getTestRepositoryLocation()
            + "/simpleimageoffline:gradle"
            + System.nanoTime();

    try {
      simpleTestProject.build(
          "--offline",
          "clean",
          "jib",
          "-Djib.useOnlyProjectCache=true",
          "-Djib.console=plain",
          "-D_TARGET_IMAGE=" + targetImage);
      Assert.fail();
    } catch (UnexpectedBuildFailure ex) {
      Assert.assertThat(
          ex.getMessage(),
          CoreMatchers.containsString("Cannot build to a container registry in offline mode"));
    }
  }

  @Test
  public void testDockerDaemon_simpleOnJava11()
      throws DigestException, IOException, InterruptedException {
    Assume.assumeTrue(isJava11RuntimeOrHigher());

    String targetImage = "localhost:6000/simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. \n",
        JibRunHelper.buildToDockerDaemonAndRun(
            simpleTestProject, targetImage, "build-java11.gradle"));
  }

  @Test
  public void testDockerDaemon_simpleWithIncompatibleJava11()
      throws DigestException, IOException, InterruptedException {
    Assume.assumeTrue(isJava11RuntimeOrHigher());

    try {
      JibRunHelper.buildToDockerDaemonAndRun(
          simpleTestProject, "willnotbuild", "build-java11-incompatible.gradle");
      Assert.fail();

    } catch (UnexpectedBuildFailure ex) {
      Assert.assertThat(
          ex.getMessage(),
          CoreMatchers.containsString(
              "Your project is using Java 11 but the base image is for Java 8, perhaps you should "
                  + "configure a Java 11-compatible base image using the 'jib.from.image' "
                  + "parameter, or set targetCompatibility = 8 or below in your build "
                  + "configuration"));
    }
  }

  @Test
  public void testDockerDaemon_simple_deprecatedExtraDirectory()
      throws DigestException, IOException, InterruptedException {
    assertExtraDirectoryDeprecationWarning("build-extra-dir-deprecated.gradle");
  }

  @Test
  public void testDockerDaemon_simple_deprecatedExtraDirectory2()
      throws DigestException, IOException, InterruptedException {
    assertExtraDirectoryDeprecationWarning("build-extra-dir-deprecated2.gradle");
  }

  @Test
  public void testDockerDaemon_simple_deprecatedExtraDirectory3()
      throws DigestException, IOException, InterruptedException {
    assertExtraDirectoryDeprecationWarning("build-extra-dir-deprecated3.gradle");
  }

  @Test
  public void testDockerDaemon_simple_multipleExtraDirectories()
      throws DigestException, IOException, InterruptedException {
    String targetImage = "localhost:6000/simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. \n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\nbaz\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildToDockerDaemonAndRun(
            simpleTestProject, targetImage, "build-extra-dirs.gradle"));
    assertLayerSize(9, targetImage); // one more than usual
  }

  @Test
  public void testDockerDaemon_simple_multipleExtraDirectoriesWithAlternativeConfig()
      throws DigestException, IOException, InterruptedException {
    String targetImage = "localhost:6000/simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. \n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\nbaz\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildToDockerDaemonAndRun(
            simpleTestProject, targetImage, "build-extra-dirs2.gradle"));
    assertLayerSize(9, targetImage); // one more than usual
  }

  @Test
  public void testBuild_complex() throws IOException, InterruptedException {
    String targetImage = "localhost:6000/compleximage:gradle" + System.nanoTime();
    Instant beforeBuild = Instant.now();
    buildAndRunComplex(targetImage, "testuser2", "testpassword2", localRegistry2);
    JibRunHelper.assertSimpleCreationTimeIsAfter(beforeBuild, targetImage);
    assertWorkingDirectory("", targetImage);
  }

  @Test
  public void testBuild_complex_sameFromAndToRegistry() throws IOException, InterruptedException {
    String targetImage = "localhost:5000/compleximage:gradle" + System.nanoTime();
    Instant beforeBuild = Instant.now();
    buildAndRunComplex(targetImage, "testuser", "testpassword", localRegistry1);
    JibRunHelper.assertSimpleCreationTimeIsAfter(beforeBuild, targetImage);
    assertWorkingDirectory("", targetImage);
  }

  @Test
  public void testDockerDaemon_simple() throws IOException, InterruptedException, DigestException {
    String targetImage = "simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        JibRunHelper.buildToDockerDaemonAndRun(simpleTestProject, targetImage, "build.gradle"));
    JibRunHelper.assertSimpleCreationTimeIsEqual(Instant.EPOCH, targetImage);
    assertDockerInspect(targetImage);
    assertWorkingDirectory("/home", targetImage);
  }

  @Test
  public void testDockerDaemon_jarContainerization()
      throws DigestException, IOException, InterruptedException {
    String targetImage = "simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. \nImplementation-Title: helloworld\nImplementation-Version: 1\n",
        JibRunHelper.buildToDockerDaemonAndRun(
            simpleTestProject, targetImage, "build-jar-containerization.gradle"));
  }

  @Test
  public void testBuild_skipDownloadingBaseImageLayers() throws IOException, InterruptedException {
    Path baseLayersCacheDirectory =
        simpleTestProject.getProjectRoot().resolve("build/jib-base-cache/layers");
    String targetImage = "localhost:6000/simpleimage:gradle" + System.nanoTime();

    buildAndRunComplex(targetImage, "testuser2", "testpassword2", localRegistry2);
    // Base image layer tarballs exist.
    Assert.assertTrue(Files.exists(baseLayersCacheDirectory));
    Assert.assertTrue(baseLayersCacheDirectory.toFile().list().length >= 2);

    buildAndRunComplex(targetImage, "testuser2", "testpassword2", localRegistry2);
    // no base layers downloaded after "gradle clean jib ..."
    Assert.assertFalse(Files.exists(baseLayersCacheDirectory));
  }

  @Test
  public void testDockerDaemon_timestampCustom()
      throws DigestException, IOException, InterruptedException {
    String targetImage = "simpleimage:gradle" + System.nanoTime();
    Assert.assertEquals(
        "Hello, world. \n2011-12-03T01:15:30Z\n",
        JibRunHelper.buildToDockerDaemonAndRun(
            simpleTestProject, targetImage, "build-timestamps-custom.gradle"));
    JibRunHelper.assertSimpleCreationTimeIsEqual(
        Instant.parse("2013-11-04T21:29:30Z"), targetImage);
  }

  @Test
  public void testDockerDaemon_timestampDeprecated()
      throws DigestException, IOException, InterruptedException {
    Instant beforeBuild = Instant.now();
    String targetImage = "simpleimage:gradle" + System.nanoTime();
    BuildResult buildResult =
        JibRunHelper.buildToDockerDaemon(
            simpleTestProject, targetImage, "build-usecurrent-deprecated.gradle");
    JibRunHelper.assertSimpleCreationTimeIsAfter(beforeBuild, targetImage);
    Assert.assertThat(
        buildResult.getOutput(),
        CoreMatchers.containsString(
            "'jib.container.useCurrentTimestamp' is deprecated; use 'jib.container.creationTime' with the value 'USE_CURRENT_TIMESTAMP' instead"));
  }

  @Test
  public void testDockerDaemon_timestampFail()
      throws InterruptedException, IOException, DigestException {
    try {
      String targetImage = "simpleimage:gradle" + System.nanoTime();
      JibRunHelper.buildToDockerDaemonAndRun(
          simpleTestProject, targetImage, "build-usecurrent-deprecated2.gradle");
      Assert.fail();
    } catch (UnexpectedBuildFailure ex) {
      Assert.assertThat(
          ex.getMessage(),
          CoreMatchers.containsString(
              "You cannot configure both 'jib.container.useCurrentTimestamp' and 'jib.container.creationTime'"));
    }
  }

  @Test
  public void testBuild_dockerClient() throws IOException, InterruptedException, DigestException {
    Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
    new Command(
            "chmod", "+x", simpleTestProject.getProjectRoot().resolve("mock-docker.sh").toString())
        .run();

    String targetImage = "simpleimage:gradle" + System.nanoTime();

    BuildResult buildResult =
        simpleTestProject.build(
            "clean",
            "jibDockerBuild",
            "-Djib.useOnlyProjectCache=true",
            "-Djib.console=plain",
            "-D_TARGET_IMAGE=" + targetImage,
            "-b=build-dockerclient.gradle",
            "--debug");
    JibRunHelper.assertBuildSuccess(
        buildResult, "jibDockerBuild", "Built image to Docker daemon as ");
    JibRunHelper.assertImageDigestAndId(simpleTestProject.getProjectRoot());
    Assert.assertThat(buildResult.getOutput(), CoreMatchers.containsString(targetImage));
    Assert.assertThat(
        buildResult.getOutput(), CoreMatchers.containsString("Docker load called. value1 value2"));
  }

  @Test
  public void testBuildTar_simple() throws IOException, InterruptedException {
    String targetImage = "simpleimage:gradle" + System.nanoTime();

    String outputPath =
        simpleTestProject.getProjectRoot().resolve("build").resolve("jib-image.tar").toString();
    BuildResult buildResult =
        simpleTestProject.build(
            "clean",
            "jibBuildTar",
            "-Djib.useOnlyProjectCache=true",
            "-Djib.console=plain",
            "-D_TARGET_IMAGE=" + targetImage);

    JibRunHelper.assertBuildSuccess(buildResult, "jibBuildTar", "Built image tarball at ");
    Assert.assertThat(buildResult.getOutput(), CoreMatchers.containsString(outputPath));

    new Command("docker", "load", "--input", outputPath).run();
    Assert.assertEquals(
        "Hello, world. An argument.\n1970-01-01T00:00:01Z\nrw-r--r--\nrw-r--r--\nfoo\ncat\n"
            + "1970-01-01T00:00:01Z\n1970-01-01T00:00:01Z\n",
        new Command("docker", "run", "--rm", targetImage).run());
    assertDockerInspect(targetImage);
    JibRunHelper.assertSimpleCreationTimeIsEqual(Instant.EPOCH, targetImage);
    assertWorkingDirectory("/home", targetImage);
  }
}
