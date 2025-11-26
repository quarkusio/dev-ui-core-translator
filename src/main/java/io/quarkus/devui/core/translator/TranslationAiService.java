package io.quarkus.devui.core.translator;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
public interface TranslationAiService {

    @SystemMessage("""
        You translate English UI strings, used in Quarkus Dev UI, to {language}. 
        You might receive numbered variables, like ${0} - take this into account when doing the translation as 
        the variable might move position in the sentence for the new language. Also note that some terms 
        (espesially technical terms), eg "Beans" in the context of the ArC extension, does not translate.
        Return only the translated text.
    """)
    String translate(@MemoryId String sessionId, @V("language") String language, @UserMessage String text);
}
