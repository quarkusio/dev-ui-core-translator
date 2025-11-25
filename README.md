# Quarkus Core Dev UI Translator

This application use AI to translate Core Quarkus Dev UI to a selected language/locale

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Running the translator locally (example: French)

1. Build the app: `./mvnw package`
2. Run the CLI: `java -jar target/quarkus-app/quarkus-run.jar translate`
3. When prompted, enter:
   - Quarkus source path (e.g. `/path/to/quarkus/extensions`)
   - Target language: `French`
   - Optional country codes (comma separated) to add dialect files, or press Enter to skip (e.g. `CA,BE`)
4. The command will create `fr.js` under each `i18n` folder so the locales show up in Dev UI. If you defined any
   optional country codes (for dialects) those will show up as `fr-CA.js` for example.

## Configuring your OpenAI key in Dev UI

1. Start dev mode: `./mvnw quarkus:dev`
2. Open Dev UI at <http://localhost:8080/q/dev> and open the Config Editor.
3. Search for `quarkus.langchain4j.openai.api-key` and paste your OpenAI key.
4. Save; Quarkus will hot-reload and the key will be used by the translator. Prefer env vars outside dev: `QUARKUS_LANGCHAIN4J_OPENAI_API_KEY=...`.
