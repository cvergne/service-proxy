/*
 * Copyright 2016 predic8 GmbH, www.predic8.com
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.predic8.membrane.core.interceptor.oauth2.request;

import com.fasterxml.jackson.core.JsonGenerator;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.*;
import com.predic8.membrane.core.interceptor.authentication.session.SessionManager;
import com.predic8.membrane.core.interceptor.oauth2.OAuth2AuthorizationServerInterceptor;
import com.predic8.membrane.core.interceptor.oauth2.ReusableJsonGenerator;
import com.predic8.membrane.core.util.URLParamUtil;
import com.predic8.schema.restriction.NormalizedStringRestriction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

public abstract class ParameterizedRequest {

    public static final String REDIRECT_URI = "redirect_uri";
    public static final String CLIENT_ID = "client_id";
    public static final String RESPONSE_TYPE = "response_type";
    public static final String SCOPE = "scope";
    public static final String STATE = "state";
    public static final String PROMPT = "prompt";
    public static final String SCOPE_INVALID = "scope_invalid";
    public static final String CODE = "code";
    public static final String CLIENT_SECRET = "client_secret";

    protected Exchange exc;
    protected OAuth2AuthorizationServerInterceptor authServer;
    protected Map<String,String> params;
    protected ReusableJsonGenerator jsonGen;

    protected abstract Response checkForMissingParameters() throws Exception;
    protected abstract Response validateWithParameters() throws Exception;
    protected abstract Response getResponse() throws Exception;

    public Response validateRequest() throws Exception {
        Response resp;
        resp = checkForMissingParameters();
        if(resp.getClass() != NoResponse.class)
            return resp;
        resp = validateWithParameters();
        if(resp.getClass() != NoResponse.class)
            return resp;
        return getResponse();
    }


    public ParameterizedRequest(OAuth2AuthorizationServerInterceptor authServer, Exchange exc) throws Exception {
        this.authServer = authServer;
        this.exc = exc;
        this.params = getValidParams(exc);
        this.jsonGen = new ReusableJsonGenerator();
    }

    private Map<String, String> getValidParams(Exchange exc) throws Exception {
        Map<String, String> params = URLParamUtil.getParams(authServer.getRouter().getUriFactory(), exc);
        removeEmptyParams(params);
        return params;
    }

    protected void removeEmptyParams(Map<String, String> params) {
        ArrayList<String> toRemove = new ArrayList<String>();
        for (String paramName : params.keySet()) {
            if (params.get(paramName).isEmpty())
                toRemove.add(paramName);
        }
        for(String paramName : toRemove)
            params.remove(paramName);
    }

    protected Response createParameterizedJsonErrorResponse(Exchange exc, String... params) throws IOException {
        if (params.length % 2 != 0)
            throw new IllegalArgumentException("The number of strings passed as params is not even");
        String json;
        synchronized (jsonGen) {
            JsonGenerator gen = jsonGen.resetAndGet();
            gen.writeStartObject();
            for (int i = 0; i < params.length; i += 2)
                gen.writeObjectField(params[i], params[i + 1]);
            gen.writeEndObject();
            json = jsonGen.getJson();
        }

        return Response.badRequest()
                .body(json)
                .contentType(MimeType.APPLICATION_JSON_UTF8)
                .dontCache()
                .build();
    }

    protected Response createParameterizedFormUrlencodedRedirect(Exchange exc, String state, String url) {
        if (state != null)
            url += "&state=" + state;
        return Response.redirect(url, false).header(Header.CONTENT_TYPE, "application/x-www-form-urlencoded").bodyEmpty().dontCache().build();
    }

    protected Response buildWwwAuthenticateErrorResponse(Response.ResponseBuilder builder, String errorValue) {
        return builder.bodyEmpty().header(Header.WWW_AUTHENTICATE, authServer.getTokenGenerator().getTokenType() + " error=\""+errorValue+"\"").build();
    }

    protected boolean isAbsoluteUri(String givenRedirect_uri) {
        try {
            // Doing it this way as URIs scheme seems to be wrong
            String[] split = givenRedirect_uri.split("://");
            return split.length == 2;
        } catch (Exception ignored) {
            return false;
        }
    }

    protected HeaderField extraxtSessionHeader(Message msg) {
        for (HeaderField h : msg.getHeader().getAllHeaderFields()) {
            if (h.getHeaderName().equals("Set-Cookie")) {
                return h;
            } else if (h.getHeaderName().equals("Cookie")) {
                h.setHeaderName(new HeaderName("Set-Cookie"));
                return h;
            }
        }
        throw new RuntimeException();
    }

    protected static String extractSessionId(HeaderField sessionHeader) {
        for (String s : sessionHeader.getValue().split(" ")) {
            if (s.startsWith("SESSIONID=")) {
                return s.substring(10);
            }
        }
        throw new RuntimeException("SessionId not found in Session header!");
    }

    protected SessionManager.Session createSession(Exchange exc, String sessionId){
        synchronized (authServer.getSessionManager()) {
            return authServer.getSessionManager().createSession(exc, sessionId);
        }
    }

    protected SessionManager.Session getSession(Exchange exc) {
        SessionManager.Session session;
        synchronized (authServer.getSessionManager()) {
            session = authServer.getSessionManager().getSession(exc.getRequest());
        }
        return session;
    }

    protected void addParams(SessionManager.Session session, Map<String,String> params){
        synchronized (session) {
            session.getUserAttributes().putAll(params);
        }
    }

    protected boolean isOpenIdScope(String scope) {
        if (scope.contains("openid")) {
            String[] split = scope.split(" ");
            for (String singleScope : split)
                if (singleScope.equals("openid"))
                    return true;
        }
        return false;
    }

    public String getPrompt() {
        return params.get(PROMPT);
    }

    public String getClientId() {
        return params.get(CLIENT_ID);
    }

    public String getRedirectUri() {
        return params.get(REDIRECT_URI);
    }

    public String getResponseType() {
        return params.get(RESPONSE_TYPE);
    }

    public String getScope() {
        return params.get(SCOPE);
    }

    public String getState() {
        return params.get(STATE);
    }

    public void setScope(String scope){
        params.put(SCOPE,scope);
    }

    public void setScopeInvalid(String invalidScopes){
        params.put(SCOPE_INVALID,invalidScopes);
    }

    public String getCode(){return params.get(CODE);}

    public String getClientSecret(){return params.get(CLIENT_SECRET);}

}
