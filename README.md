# gzoltar-defects4j-tdp-implementation
1 . Add the following pom depenceies:
<dependency>
      <groupId>com.gzoltar</groupId>
      <artifactId>com.gzoltar.fl</artifactId>
      <version>1.7.3</version>
    </dependency>

    <!-- GZoltar core APIs -->
    <dependency>
      <groupId>com.gzoltar</groupId>
      <artifactId>com.gzoltar.core</artifactId>
      <version>1.7.3</version>
    </dependency>
2. Run mvn clean compile test-compile
3. Run gzoltar vscode extention
4. Run 
mvn exec:java \
  -Dexec.mainClass="demo.gzoltar.EmbeddedSFL" \
  -Dexec.args=".gzoltar/sfl/txt" \
  -Dexec.classpathScope=test

5. mvn exec:java \
  -Dexec.mainClass="demo.gzoltar.TDPAlgorithm" \
  -Dexec.args=".gzoltar/sfl/txt" \
  -Dexec.classpathScope=test 
