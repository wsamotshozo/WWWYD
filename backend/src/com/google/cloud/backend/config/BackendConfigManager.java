/*
 * Copyright (c) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.backend.config;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;

import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Class that manages the backend configuration stored in App Engine Datastore.
 */
public class BackendConfigManager {

  /**
   * Kind name for storing backend configuration.
   */
  public static final String CONFIGURATION_ENTITY_KIND = "_BackendConfiguration";
  static final String AUTHENTICATION_MODE = "authMode";
  static final String ANDROID_CLIENT_ID = "androidClientId";
  static final String IOS_CLIENT_ID = "iOsClientId";
  static final String AUDIENCE = "audience";
  static final String PUSH_ENABLED = "pushEnabled";
  static final String ANDROID_GCM_KEY = "gCMKey";
  static final String PUSH_NOTIFICATION_CERT_PASSWORD = "pushCertPasswd";
  static final String PUSH_NOTIFICATION_CERT_BINARY = "pushCertBinary";
  static final String MEMCACHE_FILE_KEY = "memcache file key";
  static final String PKCS12_BASE64_PREFIX = "pkcs12;base64,";

  private static final String PER_APP_SECRET_KEY = "secretKey";
  private static final String CURRENT_CONFIGURATION = "Current";

  private final DatastoreService datastoreService;
  private final MemcacheService memcache;

  private final CloudEndpointsConfigManager endpointsConfigManager;
  private static final Logger log = Logger.getLogger(BackendConfigManager.class.getName());

  /**
   * Authentication Mode for the backend
   */
  public enum AuthMode {
    /**
     * All requests from the clients to APIs exposed over Cloud Endpoints should
     * be rejected
     */
    LOCKED,
    /**
     * All unauthenticated requests from the clients should be allowed. Useful
     * during early development.
     */
    OPEN,
    /**
     * Only calls from the explicitly listed clients should be allowed. The list
     * of client ids and audiences can be passed to setConfiguration() method.
     */
    CLIENT_ID
  }

  /**
   * Default constructor.
   */
  public BackendConfigManager() {
    this(DatastoreServiceFactory.getDatastoreService(),
        MemcacheServiceFactory.getMemcacheService());
  }

  /**
   * Constructs and instance of the class using specified DatastoreService.
   * 
   * @param datastoreService
   *          datastoreService that will be used for persistence.
   */
  public BackendConfigManager(DatastoreService datastoreService, MemcacheService memcache) {
    this.datastoreService = datastoreService;
    this.memcache = memcache;
    this.endpointsConfigManager = new CloudEndpointsConfigManager(datastoreService, memcache);
  }

  /**
   * Returns the current configuration of the backend.
   * 
   * @result Entity that represents the current configurations of the backend.
   */
  protected Entity getConfiguration() {

    // check memcache
    Key key = getKey();
    Entity config = (Entity) memcache.get(getMemKeyForConfigEntity(key));
    if (config != null) {
      return config;
    }

    // get from datastore
    try {
      config = datastoreService.get(key);
    } catch (EntityNotFoundException e) {
      // Default to the LOCKED authentication mode and disabled push
      // notification
      config = new Entity(key);
      config.setProperty(AUTHENTICATION_MODE, AuthMode.LOCKED.name());
      config.setProperty(PUSH_ENABLED, false);

      // Generate unique secret for this app to be used for XSRF token
      SecureRandom rnd = new SecureRandom();
      String secret = new BigInteger(256, rnd).toString();
      config.setProperty(PER_APP_SECRET_KEY, secret);

      datastoreService.put(config);
    }

    // put the config entity to memcache and return it
    memcache.put(getMemKeyForConfigEntity(key), config);
    return config;
  }

  protected void setConfiguration(String authMode, String androidClientId, String iOSClientId,
      String audience, boolean pushEnabled, String androidGCMKey, String pushCertPasswd,
      String pushCertBase64String) {
    // put config entity into Datastore and Memcache
    Key key = getKey();
    Entity configuration;
    try {
      configuration = datastoreService.get(key);
    } catch (EntityNotFoundException e) {
      throw new IllegalStateException(e);
    }
    configuration.setProperty(AUTHENTICATION_MODE, authMode);
    configuration.setProperty(ANDROID_CLIENT_ID, androidClientId);
    configuration.setProperty(IOS_CLIENT_ID, iOSClientId);
    configuration.setProperty(AUDIENCE, audience);
    configuration.setProperty(PUSH_ENABLED, pushEnabled);
    configuration.setProperty(ANDROID_GCM_KEY, androidGCMKey);
    configuration.setProperty(PUSH_NOTIFICATION_CERT_PASSWORD, pushCertPasswd);
    if (pushCertBase64String != null && !pushCertBase64String.isEmpty()) {
      Text data = removeClientHeaderFromData(pushCertBase64String);
      if (data == null) {
        log.severe("Input file is not saved as it is not in expected format and encoding");
      } else {
        configuration.setProperty(PUSH_NOTIFICATION_CERT_BINARY, data);
      }
    }

    datastoreService.put(configuration);
    memcache.put(getMemKeyForConfigEntity(key), configuration);

    // Set endpoints auth config using client Ids that are not empty.
    List<String> clientIds = new ArrayList<String>();
    List<String> audiences = new ArrayList<String>();

    if (androidClientId != null && !androidClientId.isEmpty()) {
      clientIds.add(androidClientId);
    }

    if (iOSClientId != null && !iOSClientId.isEmpty()) {
      clientIds.add(iOSClientId);
    }

    // Android client requires both Android Client ID and Web Client ID.
    // The latter is the same as audience field.
    if (audience != null && !audience.isEmpty()) {
      clientIds.add(audience);
      audiences.add(audience);
    } else {
      audiences.add(new String());
    }

    if (clientIds.size() == 0) {
      clientIds.add(new String());
    }

    endpointsConfigManager.setAuthenticationInfo(clientIds, audiences);
  }

  private Key getKey() {
    return KeyFactory.createKey(CONFIGURATION_ENTITY_KIND, CURRENT_CONFIGURATION);
  }

  private String getMemKeyForConfigEntity(Key key) {
    return CONFIGURATION_ENTITY_KIND + KeyFactory.keyToString(key);
  }

  /**
   * Returns {@link AuthMode} of the current configuration.
   * 
   * @return {@link AuthMode} of the current configuration.
   */
  public AuthMode getAuthMode() {
    return AuthMode.valueOf((String) getConfiguration().getProperty(AUTHENTICATION_MODE));
  }

  /**
   * Returns GCM API key.
   * 
   * @return GCM API key.
   */
  public String getGcmKey() {
    return (String) getConfiguration().getProperty(ANDROID_GCM_KEY);
  }

  /***
   * Returns Push Notification Certificate password.
   * 
   * @return Push Notification Certificate password.
   */
  public String getPushCertPassword() {
    return (String) getConfiguration().getProperty(PUSH_NOTIFICATION_CERT_PASSWORD);
  }

  /**
   * Return the push notification certificate as a base64-decoded input stream
   * 
   * @return the push notification certificate as a base64-decoded input stream or null
   *     if Datastore returns nothing 
   */
  public InputStream getPushNotificationCertificate() {
    Text binary = (Text) getConfiguration().getProperty(PUSH_NOTIFICATION_CERT_BINARY);
    if (binary == null) {
      return null;
    }

    return new ByteArrayInputStream(Base64.decodeBase64(binary.getValue().getBytes()));
  }

  /**
   * Returns true if Push Notification is enabled.
   * 
   * @return true if Push Notification is enabled, false otherwise.
   */
  public boolean isPushEnabled() {
    return (Boolean) getConfiguration().getProperty(PUSH_ENABLED);
  }

  protected String getSecretKey() {
    return (String) getConfiguration().getProperty(PER_APP_SECRET_KEY);
  }

  /**
   * Based64 encoded data from the front end contains prefix 
   * (i.e. "data:application/x-pkcs12;base64,"), this method is to extract the data without 
   * the prefix
   * 
   * @param base64String from client
   * @return extracted data without prefix
   */
  private Text removeClientHeaderFromData(String base64String) {
    int index = base64String.indexOf(PKCS12_BASE64_PREFIX);
    if (index < 0) {
      return null;
    }

    String data = base64String.substring(index + PKCS12_BASE64_PREFIX.length());
    if (Base64.isBase64(data)) {
      return new Text(data);
    } else {
      return null;
    }
  }
}
