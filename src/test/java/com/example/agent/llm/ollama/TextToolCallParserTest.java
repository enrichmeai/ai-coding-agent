package com.example.agent.llm.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToolCallParserTest {

    private final TextToolCallParser parser = new TextToolCallParser(new ObjectMapper());

    @Test
    void recoversQwenXmlCallAndStripsItFromTheText() {
        // Verbatim from qwen3-coder:30b via Ollama, trailing </tool_call> included.
        String text = "I'll check the files in the workspace directory for you.\n\n"
                + "<function=list_dir>\n<parameter=path>\n.\n</parameter>\n</function>\n</tool_call>";

        TextToolCallParser.Result result = parser.parse(text);

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("list_dir");
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("path", ".");
        assertThat(result.toolCalls().get(0).id()).startsWith("call_");
        assertThat(result.text()).isEqualTo("I'll check the files in the workspace directory for you.");
    }

    @Test
    void recoversMultipleXmlCallsAndAllTheirParameters() {
        String text = "<function=write_file>\n<parameter=path>\na.txt\n</parameter>\n"
                + "<parameter=content>\nhello\n</parameter>\n</function>\n"
                + "<function=read_file>\n<parameter=path>\nb.txt\n</parameter>\n</function>";

        TextToolCallParser.Result result = parser.parse(text);

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).arguments())
                .containsEntry("path", "a.txt")
                .containsEntry("content", "hello");
        assertThat(result.toolCalls().get(1).name()).isEqualTo("read_file");
    }

    @Test
    void recoversFencedJsonCall() {
        // qwen2.5-coder:3b's failure mode.
        String text = "```json\n{\"name\": \"write_file\", \"arguments\": {\"path\": \"hello.txt\", \"content\": \"hi\"}}\n```";

        TextToolCallParser.Result result = parser.parse(text);

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("write_file");
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("content", "hi");
        assertThat(result.text()).isEmpty();
    }

    @Test
    void acceptsParametersAsTheArgumentsKey() {
        String text = "{\"name\": \"list_dir\", \"parameters\": {\"path\": \".\"}}";

        assertThat(parser.parse(text).toolCalls()).hasSize(1);
    }

    @Test
    void leavesProseThatMerelyMentionsAToolAlone() {
        String text = "You can call write_file with a path and content, "
                + "or use {\"config\": true} to change settings.";

        TextToolCallParser.Result result = parser.parse(text);

        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void handlesNullAndBlankText() {
        assertThat(parser.parse(null).toolCalls()).isEmpty();
        assertThat(parser.parse("   ").toolCalls()).isEmpty();
    }
}
