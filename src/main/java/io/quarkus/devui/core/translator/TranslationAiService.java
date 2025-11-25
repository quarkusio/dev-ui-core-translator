package io.quarkus.devui.core.translator;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface TranslationAiService {

    @SystemMessage("""
        You translate English UI strings, used in Quarkus Dev UI, to {language}. 
        You might receive numbered variables, like ${0}. Take this into account when doing the translation as 
        the var might move position in the sentence for the new language. Also note that some terms 
        (espesially technical terms), like "Beans" in the context or the ArC extension, does not translate.
        Return only the translated text.
    """)
    String translate(@V("language") String language, @UserMessage String text);
}
