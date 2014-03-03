package com.hubspot.jackson.jaxrs;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;

@Provider
@Produces(MediaType.APPLICATION_JSON)
public class PropertyFilteringMessageBodyWriter implements MessageBodyWriter<Object> {

  @Context
  Application application;

  @Context
  UriInfo uriInfo;

  private volatile JacksonJsonProvider delegate;

  @Override
  public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
    return isJsonType(mediaType) &&
           filteringEnabled(annotations) &&
           getJsonProvider().isWriteable(type, genericType, annotations, mediaType);
  }

  @Override
  public long getSize(Object object, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
    return -1;
  }

  @Override
  public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                      MultivaluedMap<String, Object> httpHeaders, OutputStream os) throws IOException {
    PropertyFiltering annotation = findPropertyFiltering(annotations);

    PropertyFilter propertyFilter = new PropertyFilter(uriInfo.getQueryParameters().get(annotation.using()));
    if (!propertyFilter.hasFilters()) {
      write(o, type, genericType, annotations, mediaType, httpHeaders, os);
      return;
    }

    Timer timer = Metrics.defaultRegistry().newTimer(PropertyFilteringMessageBodyWriter.class, "filter");
    TimerContext context = timer.time();

    try {
      JsonNode tree = getJsonProvider().locateMapper(type, mediaType).valueToTree(o);
      propertyFilter.filter(tree);
      write(tree, tree.getClass(), tree.getClass(), annotations, mediaType, httpHeaders, os);
    } finally {
      context.stop();
    }
  }

  private void write(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                     MultivaluedMap<String, Object> httpHeaders, OutputStream os) throws IOException {
    getJsonProvider().writeTo(o, type, genericType, annotations, mediaType, httpHeaders, os);
  }

  private JacksonJsonProvider getJsonProvider() {
    if (delegate != null) {
      return delegate;
    }

    synchronized (this) {
      if (delegate != null) {
        return delegate;
      }

      for (Object o : application.getSingletons()) {
        if (o instanceof JacksonJsonProvider) {
          delegate = (JacksonJsonProvider) o;
          return delegate;
        }
      }

      delegate = new JacksonJsonProvider();
      return delegate;
    }
  }

  private static boolean isJsonType(MediaType mediaType) {
    return MediaType.APPLICATION_JSON_TYPE.getType().equals(mediaType.getType()) &&
            MediaType.APPLICATION_JSON_TYPE.getSubtype().equals(mediaType.getSubtype());
  }

  private static boolean filteringEnabled(Annotation... annotations) {
    return findPropertyFiltering(annotations) != null;
  }

  private static PropertyFiltering findPropertyFiltering(Annotation... annotations) {
    for (Annotation annotation : annotations) {
      if (annotation.annotationType() == PropertyFiltering.class) {
        return (PropertyFiltering) annotation;
      }
    }

    return null;
  }
}
