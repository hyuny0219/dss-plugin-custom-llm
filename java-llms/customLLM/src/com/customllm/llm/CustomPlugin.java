package com.customllm.llm;

import com.dataiku.dip.utils.DKULogger;
import com.dataiku.dip.llm.online.LLMClient.SimpleEmbeddingResponse;
import com.dataiku.dip.llm.online.LLMClient.EmbeddingQuery;
import com.dataiku.dip.llm.online.LLMClient.EmbeddingSettings;
import com.dataiku.common.rpc.ExternalJSONAPIClient;
import com.dataiku.common.rpc.ExternalJSONAPIClient.EntityAndRequest;
import com.dataiku.dip.ApplicationConfigurator;
import com.dataiku.dip.connections.AbstractLLMConnection.HTTPBasedLLMNetworkSettings;
import com.dataiku.dip.custom.PluginSettingsResolver.ResolvedSettings;
import com.dataiku.dip.llm.LLMStructuredRef;
import com.dataiku.dip.llm.custom.CustomLLMClient;
import com.dataiku.dip.llm.online.LLMClient.ChatMessage;
import com.dataiku.dip.llm.online.LLMClient.CompletionQuery;
import com.dataiku.dip.llm.online.LLMClient.SimpleCompletionResponse;
import com.dataiku.dip.llm.online.LLMClient.StreamedCompletionResponseChunk;
import com.dataiku.dip.llm.online.LLMClient.StreamedCompletionResponseConsumer;
import com.dataiku.dip.llm.online.LLMClient.StreamedCompletionResponseFooter;
import com.dataiku.dip.llm.promptstudio.PromptStudio;
import com.dataiku.dip.llm.utils.OnlineLLMUtils;
import com.dataiku.dip.resourceusage.ComputeResourceUsage;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.InternalLLMUsageData;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageData;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageType;
import com.dataiku.dip.streaming.endpoints.httpsse.SSEDecoder;
import com.dataiku.dip.streaming.endpoints.httpsse.SSEDecoder.HTTPSSEEvent;
import com.dataiku.dip.util.JsonUtils;
import com.dataiku.dip.utils.JF;
import com.dataiku.dip.utils.JF.ObjectBuilder;
import com.dataiku.dip.utils.JSON;
import com.dataiku.dss.shadelib.org.apache.http.client.methods.HttpGet;
import com.dataiku.dss.shadelib.org.apache.http.client.methods.HttpPost;
import com.dataiku.dss.shadelib.org.apache.http.client.methods.HttpPut;
import com.dataiku.dss.shadelib.org.apache.http.client.methods.HttpDelete;
import com.dataiku.dss.shadelib.org.apache.http.impl.client.LaxRedirectStrategy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CustomPlugin extends CustomLLMClient {
    private static final DKULogger logger = DKULogger.getLogger("dku.llm.customplugin.custom.llm");

    public CustomPlugin() {
    }

    private String endpointUrl;
    private String model;
    private String inputType;
    private ExternalJSONAPIClient client;
    private final InternalLLMUsageData usageData = new LLMUsageData();
    private final HTTPBasedLLMNetworkSettings networkSettings = new HTTPBasedLLMNetworkSettings();
    private int maxParallel = 1;

    private static class RawChatCompletionMessage {
        String role;
        String content;
    }

    private static class RawChatCompletionChoice {
        RawChatCompletionMessage message;
    }

    private static class RawUsageResponse {
        int total_tokens;
        int prompt_tokens;
        int completion_tokens;
    }

    private static class RawChatCompletionResponse {
        List<RawChatCompletionChoice> choices;
        RawUsageResponse usage;
    }

    private static class EmbeddingResponse {
        List<EmbeddingResult> data = new ArrayList<>();
        RawUsageResponse usage;

    }

    private static class EmbeddingResult {
        double[] embedding;
    }
    
    public void init(ResolvedSettings settings) {
        endpointUrl = settings.config.get("endpoint_url").getAsString();
        model = settings.config.get("model").getAsString();
        maxParallel = settings.config.get("maxParallelism").getAsNumber().intValue();

        networkSettings.queryTimeoutMS = settings.config.get("networkTimeout").getAsNumber().intValue();
        networkSettings.maxRetries = settings.config.get("maxRetries").getAsNumber().intValue();
        networkSettings.initialRetryDelayMS = settings.config.get("firstRetryDelay").getAsNumber().longValue();
        networkSettings.retryDelayScalingFactor = settings.config.get("retryDelayScale").getAsNumber().doubleValue();

        // access_token을 STRING으로 직접 받음
        String access_token = settings.config.get("apikeys").getAsJsonObject().get("api_key").getAsString();
        String sendSystemNameValue = settings.config.get("apikeys").getAsJsonObject().get("send_system_name_value").getAsString();
        String userIdValue = settings.config.get("apikeys").getAsJsonObject().get("user_id_value").getAsString();
        String promptMsgIdValue = settings.config.get("apikeys").getAsJsonObject().get("prompt_msg_id_value").getAsString();
        String completionMsgIdValue = settings.config.get("apikeys").getAsJsonObject().get("completion_msg_id_value").getAsString();
        String xDepTicketValue = settings.config.get("apikeys").getAsJsonObject().get("x_dep_ticket_value").getAsString();

        client = new ExternalJSONAPIClient(endpointUrl, null, true, ApplicationConfigurator.getProxySettings(),
                OnlineLLMUtils.getLLMResponseRetryStrategy(networkSettings),
                (builder) -> OnlineLLMUtils.add429RetryStrategy(builder, networkSettings)) {
            @Override
            protected HttpGet newGet(String path) {
                HttpGet get = new HttpGet(path);
                setAdditionalHeadersInRequest(get);
                get.addHeader("Authorization", access_token);
                get.addHeader("Send-System-Name", sendSystemNameValue);
                get.addHeader("User-id", userIdValue);
                get.addHeader("Prompt-Msg-Id", promptMsgIdValue);
                get.addHeader("Completion-Msg_Id", completionMsgIdValue);
                get.addHeader("x-dep-ticket", xDepTicketValue);
                
                return get;
            }

            @Override
            protected HttpPost newPost(String path) {
                HttpPost post = new HttpPost(path);
                setAdditionalHeadersInRequest(post);
                post.addHeader("Authorization", access_token);
                post.addHeader("Send-System-Name", sendSystemNameValue);
                post.addHeader("User-id", userIdValue);
                post.addHeader("Prompt-Msg-Id", promptMsgIdValue);
                post.addHeader("Completion-Msg_Id", completionMsgIdValue);
                post.addHeader("x-dep-ticket", xDepTicketValue);
                
                return post;
            }

            @Override
            protected HttpPut newPut(String path) {
                throw new IllegalArgumentException("unimplemented");
            }

            @Override
            protected HttpDelete newDelete(String path) {
                throw new IllegalArgumentException("unimplemented");
            }
        };
    }

    @Override
    public int getMaxParallelism() {
        return maxParallel;
    }

    @Override
    public List<SimpleCompletionResponse> completeBatch(List<CompletionQuery> completionQueries) throws IOException {
        List<SimpleCompletionResponse> ret = new ArrayList<>();
        for (CompletionQuery query : completionQueries) {
            long before = System.currentTimeMillis();
            SimpleCompletionResponse scr = chatComplete(model, query.messages, query.settings.maxOutputTokens,
                    query.settings.temperature, query.settings.topP, query.settings.stopSequences);

            synchronized (usageData) {
                usageData.totalComputationTimeMS += (System.currentTimeMillis() - before);
                usageData.totalPromptTokens += scr.promptTokens;
                usageData.totalCompletionTokens += scr.completionTokens;
            }

            ret.add(scr);
        }
        return ret;
    }

    private void addMessagesInObject(ObjectBuilder ob, List<ChatMessage> messages) {
        JsonArray jsonMessages = new JsonArray();

        messages.forEach(m -> {
            JF.ObjectBuilder msgBuilder = JF.obj().with("role", m.role);
            msgBuilder.with("content", m.getText());
            jsonMessages.add(msgBuilder.get());
        });
        ob.with("messages", jsonMessages);
    }

    private void addSettingsInObject(ObjectBuilder ob, String model, Integer maxTokens, Double temperature,
            Double topP, List<String> stopSequences) {
        ob.with("model", model);

        if (maxTokens != null) {
            ob.with("max_tokens", maxTokens);
        }
        if (temperature != null) {
            ob.with("temperature", temperature);
        }
        if (topP != null) {
            ob.with("top_p", topP);
        }
        if (stopSequences != null && !stopSequences.isEmpty()) {
            ob.with("stop", stopSequences);
        }
    }

    public SimpleCompletionResponse chatComplete(String model, List<ChatMessage> messages, Integer maxTokens,
            Double temperature, Double topP, List<String> stopSequences) throws IOException {
        ObjectBuilder ob = JF.obj();

        addMessagesInObject(ob, messages);
        addSettingsInObject(ob, model, maxTokens, temperature, topP, stopSequences);

        RawChatCompletionResponse rcr = client.postObjectToJSON(endpointUrl, networkSettings.queryTimeoutMS,
                RawChatCompletionResponse.class, ob.get());

        logger.info("Raw Chat response: " + JSON.pretty(rcr));

        if (rcr.choices == null || rcr.choices.isEmpty()) {
            throw new IOException("Chat did not respond with valid completion");
        }

        SimpleCompletionResponse ret = new SimpleCompletionResponse();
        ret.text = rcr.choices.get(0).message.content;
        ret.promptTokens = rcr.usage.prompt_tokens;
        ret.completionTokens = rcr.usage.completion_tokens;
        return ret;
    }

    @Override
    public ComputeResourceUsage getTotalCRU(LLMUsageType llmUsageType, PromptStudio.LLMStructuredRef legacyRef) {
        return getTotalCRU(llmUsageType, LLMStructuredRef.decodeId(legacyRef.id));
    }

    public ComputeResourceUsage getTotalCRU(LLMUsageType usageType, LLMStructuredRef llmRef) {
        ComputeResourceUsage cru = new ComputeResourceUsage();
        cru.setupLLMUsage(usageType, llmRef.connection, llmRef.type.toString(), llmRef.id);
        cru.llmUsage.setFromInternal(this.usageData);
        return cru;
    }

    public boolean supportsStream() {
        return true;
    }

    public void streamComplete(CompletionQuery query, StreamedCompletionResponseConsumer consumer) throws Exception {
        streamChatComplete(consumer, model, query.messages, query.settings.maxOutputTokens,
                query.settings.temperature, query.settings.topP, query.settings.stopSequences);
    }

    public void streamChatComplete(StreamedCompletionResponseConsumer consumer, String model,
            List<ChatMessage> messages, Integer maxTokens, Double temperature, Double topP,
            List<String> stopSequences) throws Exception {
        ObjectBuilder ob = JF.obj();

        addMessagesInObject(ob, messages);
        addSettingsInObject(ob, model, maxTokens, temperature, topP, stopSequences);
        ob.with("stream", true);

        logger.info("Custom chat completion: " + JSON.pretty(ob.get()));

        EntityAndRequest ear = client.postJSONToStreamAndRequest(endpointUrl, networkSettings.queryTimeoutMS, ob.get());
        SSEDecoder decoder = new SSEDecoder(ear.entity.getContent());

        consumer.onStreamStarted();

        while (true) {
            HTTPSSEEvent event = decoder.next();
            if (logger.isTraceEnabled()) {
                logger.trace("Received raw event from LLM: " + JSON.json(event));
            }

            if (event == null || event.data == null) {
                logger.info("End of LLM stream");
                break;
            }

            if (event.data.equals("[DONE]")) {
                logger.info("Received explicit end marker from LLM stream");
                break;
            }

            JsonObject data = JSON.parse(event.data, JsonObject.class);
            String chunkText = JsonUtils.getOrNullStr(data, "choices", 0, "delta", "content");

            if (chunkText != null) {
                StreamedCompletionResponseChunk chunk = new StreamedCompletionResponseChunk();
                chunk.text = chunkText;
                consumer.onStreamChunk(chunk);
            }
        }

        StreamedCompletionResponseFooter footer = new StreamedCompletionResponseFooter();
        consumer.onStreamComplete(footer);
    }

    @Override
    public List<SimpleEmbeddingResponse> embedBatch(List<EmbeddingQuery> queries) throws IOException {
        List<SimpleEmbeddingResponse> ret = new ArrayList<>();

        for (EmbeddingQuery query : queries) {
            long before = System.currentTimeMillis();
            SimpleEmbeddingResponse embeddingResponse = embed(model, query.text, inputType);

            synchronized (usageData) {
                usageData.totalComputationTimeMS += (System.currentTimeMillis() - before);
                usageData.totalPromptTokens += embeddingResponse.promptTokens;
            }

            ret.add(embeddingResponse);
        }
        return ret;
    }

    @Override
    public List<SimpleEmbeddingResponse> embedBatch(List<EmbeddingQuery> queries, EmbeddingSettings settings) throws IOException {
        return embedBatch(queries);
    }

    public SimpleEmbeddingResponse embed(String model, String text, String inputType) throws IOException {
        if (inputType == null || inputType.isEmpty()) {
            inputType = "query";
        }

        ObjectBuilder ob = JF.obj()
            .with("input", text)
            .with("model", model)
            .with("input_type", inputType);

        logger.info("raw embedding query: " + JSON.json(ob.get()));
        
        String embeddingEndpoint = endpointUrl.replace("/chat/completions", "/embeddings");
        EmbeddingResponse rer = client.postObjectToJSON(embeddingEndpoint, networkSettings.queryTimeoutMS, EmbeddingResponse.class, ob.get());
        logger.info("raw embedding response: " + JSON.json(rer));

        if (rer.data.size() != 1) {
            throw new IOException("API did not respond with valid embeddings");
        }

        SimpleEmbeddingResponse ret = new SimpleEmbeddingResponse();
        ret.embedding = rer.data.get(0).embedding;
        ret.promptTokens = rer.usage.total_tokens;
        return ret;
    }
} 