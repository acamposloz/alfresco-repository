/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2019 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.transform.client.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.transform.client.model.config.TransformConfig;
import org.alfresco.transform.client.model.config.TransformOption;
import org.alfresco.transform.client.model.config.TransformStep;
import org.alfresco.transform.client.model.config.Transformer;
import org.alfresco.util.ConfigFileFinder;
import org.apache.commons.logging.Log;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class reads multiple T-Engine config and local files and registers them all with a registry as if they were all
 * in one file. Transform options are shared between all sources.<p>
 *
 * The caller should make calls to {@link #addRemoteConfig(List, String)} and {@link #addLocalConfig(String)} followed
 * by a call to {@link #register(TransformServiceRegistryImpl)}.
 *
 * @author adavis
 */
public class CombinedConfig
{
    private static final String TRANSFORM_CONFIG = "/transform/config";

    private final Log log;

    static class TransformAndItsOrigin
    {
        final Transformer transformer;
        final String baseUrl;
        final String readFrom;

        TransformAndItsOrigin(Transformer transformer, String baseUrl, String readFrom)
        {
            this.transformer = transformer;
            this.baseUrl = baseUrl;
            this.readFrom = readFrom;
        }
    }

    Map<String, Set<TransformOption>> combinedTransformOptions = new HashMap<>();
    List<TransformAndItsOrigin> combinedTransformers = new ArrayList<>();

    private ObjectMapper jsonObjectMapper = new ObjectMapper();
    private ConfigFileFinder configFileFinder;
    private int tEngineCount;

    public CombinedConfig(Log log)
    {
        this.log = log;

        configFileFinder = new ConfigFileFinder(jsonObjectMapper)
        {
            @Override
            protected void readJson(JsonNode jsonNode, String readFrom, String baseUrl)
            {
                TransformConfig transformConfig = jsonObjectMapper.convertValue(jsonNode, TransformConfig.class);
                transformConfig.getTransformOptions().forEach((key, map) -> combinedTransformOptions.put(key, map));
                transformConfig.getTransformers().forEach(transformer -> combinedTransformers.add(
                        new TransformAndItsOrigin(transformer, baseUrl, readFrom)));
            }
        };
    }

    public boolean addLocalConfig(String path)
    {
        return configFileFinder.readFiles(path, log);
    }

    public boolean addRemoteConfig(List<String> urls, String remoteType)
    {
        boolean successReadingConfig = true;
        for (String url : urls)
        {
            if (addRemoteConfig(url, remoteType))
            {
                tEngineCount++ ;
            }
            else
            {
                successReadingConfig = false;
            }
        }
        return successReadingConfig;
    }

    private boolean addRemoteConfig(String baseUrl, String remoteType)
    {
        String url = baseUrl + TRANSFORM_CONFIG;
        HttpGet httpGet = new HttpGet(url);
        boolean successReadingConfig = true;
        try
        {
            try (CloseableHttpClient httpclient = HttpClients.createDefault())
            {
                try (CloseableHttpResponse response = execute(httpclient, httpGet))
                {
                    StatusLine statusLine = response.getStatusLine();
                    if (statusLine == null)
                    {
                        throw new AlfrescoRuntimeException(remoteType+" on " + url+" returned no status ");
                    }
                    HttpEntity resEntity = response.getEntity();
                    if (resEntity != null)
                    {
                        int statusCode = statusLine.getStatusCode();
                        if (statusCode == 200)
                        {
                            try
                            {
                                String content = getContent(resEntity);

                                try (StringReader reader = new StringReader(content))
                                {
                                    int transformCount = combinedTransformers.size();
                                    configFileFinder.readFile(reader, remoteType+" on "+baseUrl, "json", baseUrl, log);
                                    if (transformCount == combinedTransformers.size())
                                    {
                                        successReadingConfig = false;
                                    }
                                }

                                EntityUtils.consume(resEntity);
                            }
                            catch (IOException e)
                            {
                                throw new AlfrescoRuntimeException("Failed to read the returned content from "+
                                        remoteType+" on " + url, e);
                            }
                        }
                        else
                        {
                            String message = getErrorMessage(resEntity);
                            throw new AlfrescoRuntimeException(remoteType+" on " + url+" returned a " + statusCode +
                                    " status " + message);
                        }
                    }
                    else
                    {
                        throw new AlfrescoRuntimeException(remoteType+" on " + url+" did not return an entity " + url);
                    }
                }
                catch (IOException e)
                {
                    throw new AlfrescoRuntimeException("Failed to connect or to read the response from "+remoteType+
                            " on " + url, e);
                }
            }
            catch (IOException e)
            {
                throw new AlfrescoRuntimeException(remoteType+" on " + url+" failed to create an HttpClient", e);
            }
        }
        catch (AlfrescoRuntimeException e)
        {
            log.error(e.getMessage());
            successReadingConfig = false;
        }
        return successReadingConfig;
    }

    // Tests mock the return values
    CloseableHttpResponse execute(CloseableHttpClient httpclient, HttpGet httpGet) throws IOException
    {
        return httpclient.execute(httpGet);
    }

    // Tests mock the return values
    String getContent(HttpEntity resEntity) throws IOException
    {
        return EntityUtils.toString(resEntity);
    }

    // Strip out just the error message in the response
    private String getErrorMessage(HttpEntity resEntity) throws IOException
    {
        String message = "";
        String content = getContent(resEntity);
        int i = content.indexOf("\"message\":\"");
        if (i != -1)
        {
            int j = content.indexOf("\",\"path\":", i);
            if (j != -1)
            {
                message = content.substring(i+11, j);
            }
        }
        return message;
    }

    public void register(TransformServiceRegistryImpl registry)
    {
        TransformServiceRegistryImpl.Data data = registry.getData();
        data.setTEngineCount(tEngineCount);
        data.setFileCount(configFileFinder.getFileCount());

        combinedTransformers = sortTransformers(combinedTransformers);

        combinedTransformers.forEach(transformer ->
            registry.register(transformer.transformer, combinedTransformOptions,
                    transformer.baseUrl, transformer.readFrom));
    }

    // Sort transformers so there are no forward references, if that is possible.
    private static List<TransformAndItsOrigin> sortTransformers(List<TransformAndItsOrigin> original)
    {
        List<TransformAndItsOrigin> transformers = new ArrayList<>(original.size());
        List<TransformAndItsOrigin> todo = new ArrayList<>(original.size());
        Set<String> transformerNames = new HashSet<>();
        boolean added;
        do
        {
            added = false;
            for (TransformAndItsOrigin entry : original)
            {
                String name = entry.transformer.getTransformerName();
                List<TransformStep> pipeline = entry.transformer.getTransformerPipeline();
                Set<String> referencedTransformerNames = new HashSet<>();
                boolean addEntry = true;
                if (pipeline != null)
                {
                    for (TransformStep step : pipeline)
                    {
                        String stepName = step.getTransformerName();
                        referencedTransformerNames.add(stepName);
                    }
                }
                List<String> failover = entry.transformer.getTransformerFailover();
                if (failover != null)
                {
                    referencedTransformerNames.addAll(failover);
                }

                for (String referencedTransformerName : referencedTransformerNames)
                {
                    if (!transformerNames.contains(referencedTransformerName))
                    {
                        todo.add(entry);
                        addEntry = false;
                        break;
                    }
                }

                if (addEntry)
                {
                    transformers.add(entry);
                    added = true;
                    if (name != null)
                    {
                        transformerNames.add(name);
                    }
                }
            }
            original.clear();
            original.addAll(todo);
            todo.clear();
        }
        while (added && !original.isEmpty());

        transformers.addAll(todo);

        return transformers;
    }
}