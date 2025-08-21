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
import com.google.gson.JsonElement;
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

    // OpenAI 호환 Tool calling을 위한 데이터 구조들
    private static class Tool {
        String type;
        Function function;
    }

    private static class Function {
        String name;
        String description;
        JsonObject parameters;
    }

    private static class ToolCall {
        String id;
        String type;
        FunctionCall function;
    }

    private static class FunctionCall {
        String name;
        String arguments;
    }

    private static class RawChatCompletionMessage {
        String role;
        String content;
        List<ToolCall> tool_calls;  // OpenAI 호환 tool_calls 필드
    }

    private static class RawChatCompletionChoice {
        RawChatCompletionMessage message;
        String finish_reason;  // OpenAI 호환 finish_reason 필드
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
            
            // Tools 정보 추출 (CompletionQuery에서 가져옴)
            List<Tool> tools = extractToolsFromQuery(query);
            
            SimpleCompletionResponse scr = chatComplete(model, query.messages, query.settings.maxOutputTokens,
                    query.settings.temperature, query.settings.topP, query.settings.stopSequences, tools);

            synchronized (usageData) {
                usageData.totalComputationTimeMS += (System.currentTimeMillis() - before);
                usageData.totalPromptTokens += scr.promptTokens;
                usageData.totalCompletionTokens += scr.completionTokens;
            }

            ret.add(scr);
        }
        return ret;
    }

    // Tools 추출을 위한 헬퍼 메서드 - 안전한 구현
    private List<Tool> extractToolsFromQuery(CompletionQuery query) {
        // 현재는 기본적으로 null을 반환하되, 안전하게 처리
        logger.debug("Extracting tools from query - currently returning null for safety");
        return null;
    }

    // OpenAI 호환 메시지 처리 로직 - 안전한 구현
    private void addMessagesInObject(ObjectBuilder ob, List<ChatMessage> messages) {
        JsonArray jsonMessages = new JsonArray();

        messages.forEach(m -> {
            JF.ObjectBuilder msgBuilder = JF.obj().with("role", m.role);
            
            // Content가 있는 경우만 추가 (null이 아닌 경우)
            if (m.getText() != null && !m.getText().isEmpty()) {
                msgBuilder.with("content", m.getText());
            }
            
            // OpenAI 호환 tool_calls 처리 - 안전한 구현
            try {
                // Dataiku의 ChatMessage에 toolCalls 필드가 있는지 확인
                if ("assistant".equals(m.role)) {
                    // Reflection을 사용하여 toolCalls 필드 존재 여부 확인
                    try {
                        java.lang.reflect.Field toolCallsField = m.getClass().getDeclaredField("toolCalls");
                        toolCallsField.setAccessible(true);
                        Object toolCallsObj = toolCallsField.get(m);
                        
                        if (toolCallsObj != null && toolCallsObj instanceof List && !((List<?>) toolCallsObj).isEmpty()) {
                            List<?> toolCalls = (List<?>) toolCallsObj;
                            JsonArray toolCallsArray = new JsonArray();
                            
                            for (Object tc : toolCalls) {
                                if (tc instanceof ToolCall) {
                                    ToolCall toolCall = (ToolCall) tc;
                                    JF.ObjectBuilder toolCallBuilder = JF.obj()
                                        .with("id", toolCall.id)
                                        .with("type", toolCall.type);
                                    
                                    if (toolCall.function != null) {
                                        JF.ObjectBuilder functionBuilder = JF.obj()
                                            .with("name", toolCall.function.name)
                                            .with("arguments", toolCall.function.arguments);
                                        toolCallBuilder.with("function", functionBuilder.get());
                                    }
                                    
                                    toolCallsArray.add(toolCallBuilder.get());
                                }
                            }
                            
                            if (toolCallsArray.size() > 0) {
                                msgBuilder.with("tool_calls", toolCallsArray);
                            }
                        }
                    } catch (NoSuchFieldException e) {
                        // toolCalls 필드가 없으면 무시 (정상적인 경우)
                        logger.debug("ChatMessage does not have toolCalls field: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Error processing tool_calls in message: " + e.getMessage());
            }
            
            jsonMessages.add(msgBuilder.get());
        });
        ob.with("messages", jsonMessages);
    }

    // OpenAI 호환 설정 처리 로직 - 안전한 구현
    private void addSettingsInObject(ObjectBuilder ob, String model, Integer maxTokens, Double temperature,
            Double topP, List<String> stopSequences, List<Tool> tools) {
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
        
        // OpenAI 호환 tools 추가 - 안전한 구현
        if (tools != null && !tools.isEmpty()) {
            try {
                JsonArray toolsArray = new JsonArray();
                tools.forEach(tool -> {
                    try {
                        JF.ObjectBuilder toolBuilder = JF.obj().with("type", tool.type);
                        if (tool.function != null) {
                            JF.ObjectBuilder functionBuilder = JF.obj()
                                .with("name", tool.function.name)
                                .with("description", tool.function.description)
                                .with("parameters", tool.function.parameters);
                            toolBuilder.with("function", functionBuilder.get());
                        }
                        toolsArray.add(toolBuilder.get());
                    } catch (Exception e) {
                        logger.warn("Error processing tool: " + e.getMessage());
                    }
                });
                
                if (toolsArray.size() > 0) {
                    ob.with("tools", toolsArray);
                }
            } catch (Exception e) {
                logger.warn("Error adding tools to request: " + e.getMessage());
            }
        }
    }

    // OpenAI 호환 Chat Completion 메서드 - 안전한 구현
    public SimpleCompletionResponse chatComplete(String model, List<ChatMessage> messages, Integer maxTokens,
            Double temperature, Double topP, List<String> stopSequences, List<Tool> tools) throws IOException {
        ObjectBuilder ob = JF.obj();

        try {
            addMessagesInObject(ob, messages);
            addSettingsInObject(ob, model, maxTokens, temperature, topP, stopSequences, tools);

            logger.info("Sending chat completion request to: " + endpointUrl);
            logger.debug("Request payload: " + JSON.pretty(ob.get()));

            RawChatCompletionResponse rcr = client.postObjectToJSON(endpointUrl, networkSettings.queryTimeoutMS,
                    RawChatCompletionResponse.class, ob.get());

            logger.info("Raw Chat response: " + JSON.pretty(rcr));

            if (rcr.choices == null || rcr.choices.isEmpty()) {
                throw new IOException("Chat did not respond with valid completion");
            }

            SimpleCompletionResponse ret = new SimpleCompletionResponse();
            
            // 안전한 content 처리
            if (rcr.choices.get(0).message != null) {
                ret.text = rcr.choices.get(0).message.content;
                
                // OpenAI 호환 tool_calls 처리 - 타입 변환 문제 해결
                if (rcr.choices.get(0).message.tool_calls != null) {
                    try {
                        // Dataiku의 AbstractToolCall로 변환하는 로직
                        // 현재는 기본적으로 null로 설정 (실제 구현 필요)
                        ret.toolCalls = null; // TODO: ToolCall을 AbstractToolCall로 변환
                        logger.info("Tool calls received: " + rcr.choices.get(0).message.tool_calls.size());
                    } catch (Exception e) {
                        logger.warn("Error processing tool_calls in response: " + e.getMessage());
                    }
                }
            }
            
            // 안전한 usage 처리
            if (rcr.usage != null) {
                ret.promptTokens = rcr.usage.prompt_tokens;
                ret.completionTokens = rcr.usage.completion_tokens;
            }
            
            return ret;
            
        } catch (Exception e) {
            logger.error("Error in chatComplete: " + e.getMessage(), e);
            throw new IOException("Chat completion failed: " + e.getMessage(), e);
        }
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
        // Tools 정보 추출
        List<Tool> tools = extractToolsFromQuery(query);
        
        streamChatComplete(consumer, model, query.messages, query.settings.maxOutputTokens,
                query.settings.temperature, query.settings.topP, query.settings.stopSequences, tools);
    }

    // OpenAI 호환 Streaming 메서드 - 안전한 구현
    public void streamChatComplete(StreamedCompletionResponseConsumer consumer, String model,
            List<ChatMessage> messages, Integer maxTokens, Double temperature, Double topP,
            List<String> stopSequences, List<Tool> tools) throws Exception {
        ObjectBuilder ob = JF.obj();

        try {
            addMessagesInObject(ob, messages);
            addSettingsInObject(ob, model, maxTokens, temperature, topP, stopSequences, tools);
            ob.with("stream", true);

            logger.info("Custom chat completion streaming: " + JSON.pretty(ob.get()));

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

                try {
                    JsonObject data = JSON.parse(event.data, JsonObject.class);
                    String chunkText = getJsonString(data, "choices", "0", "delta", "content");

                    if (chunkText != null) {
                        StreamedCompletionResponseChunk chunk = new StreamedCompletionResponseChunk();
                        chunk.text = chunkText;
                        
                        // OpenAI 호환 tool_calls 처리 - 안전한 구현
                        try {
                            JsonObject toolCalls = getJsonObject(data, "choices", "0", "delta", "tool_calls");
                            if (toolCalls != null) {
                                // 타입 변환 문제 해결을 위해 null로 설정
                                chunk.toolCalls = null; // TODO: ToolCall을 AbstractToolCall로 변환
                            }
                        } catch (Exception e) {
                            logger.warn("Error processing tool_calls in stream: " + e.getMessage());
                        }
                        
                        consumer.onStreamChunk(chunk);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing stream event: " + e.getMessage());
                    // 스트림 오류가 발생해도 계속 진행
                }
            }

            StreamedCompletionResponseFooter footer = new StreamedCompletionResponseFooter();
            consumer.onStreamComplete(footer);
            
        } catch (Exception e) {
            logger.error("Error in streamChatComplete: " + e.getMessage(), e);
            throw e;
        }
    }

    // 안전한 JSON 접근을 위한 헬퍼 메서드들 - 개선된 버전
    private String getJsonString(JsonObject obj, String... path) {
        try {
            JsonElement element = getJsonElement(obj, path);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject getJsonObject(JsonObject obj, String... path) {
        try {
            JsonElement element = getJsonElement(obj, path);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonElement getJsonElement(JsonObject obj, String... path) {
        try {
            JsonElement current = obj;
            for (String key : path) {
                if (current == null) {
                    return null;
                }
                
                if (current.isJsonObject()) {
                    JsonObject jsonObj = current.getAsJsonObject();
                    if (jsonObj.has(key)) {
                        current = jsonObj.get(key);
                    } else {
                        return null;
                    }
                } else if (current.isJsonArray()) {
                    JsonArray jsonArray = current.getAsJsonArray();
                    try {
                        int index = Integer.parseInt(key);
                        if (index >= 0 && index < jsonArray.size()) {
                            current = jsonArray.get(index);
                        } else {
                            return null;
                        }
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            return current;
        } catch (Exception e) {
            return null;
        }
    }

    // Tool calls 파싱을 위한 헬퍼 메서드 - 안전한 구현
    private List<ToolCall> parseToolCalls(JsonObject toolCallsData) {
        List<ToolCall> toolCalls = new ArrayList<>();
        
        try {
            if (toolCallsData.has("id")) {
                // 단일 tool call
                ToolCall toolCall = new ToolCall();
                toolCall.id = getJsonString(toolCallsData, "id");
                toolCall.type = getJsonString(toolCallsData, "type");
                
                JsonObject functionData = getJsonObject(toolCallsData, "function");
                if (functionData != null) {
                    FunctionCall functionCall = new FunctionCall();
                    functionCall.name = getJsonString(functionData, "name");
                    functionCall.arguments = getJsonString(functionData, "arguments");
                    toolCall.function = functionCall;
                }
                
                toolCalls.add(toolCall);
            } else if (toolCallsData.isJsonArray()) {
                // 배열 형태의 tool calls
                JsonArray toolCallsArray = toolCallsData.getAsJsonArray();
                for (int i = 0; i < toolCallsArray.size(); i++) {
                    try {
                        JsonObject toolCallData = toolCallsArray.get(i).getAsJsonObject();
                        ToolCall toolCall = new ToolCall();
                        toolCall.id = getJsonString(toolCallData, "id");
                        toolCall.type = getJsonString(toolCallData, "type");
                        
                        JsonObject functionData = getJsonObject(toolCallData, "function");
                        if (functionData != null) {
                            FunctionCall functionCall = new FunctionCall();
                            functionCall.name = getJsonString(functionData, "name");
                            functionCall.arguments = getJsonString(functionData, "arguments");
                            toolCall.function = functionCall;
                        }
                        
                        toolCalls.add(toolCall);
                    } catch (Exception e) {
                        logger.warn("Error parsing tool call at index " + i + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing tool calls: " + e.getMessage());
        }
        
        return toolCalls;
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