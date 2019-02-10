/**
 * ***************************************************************************** Cloud Foundry
 * Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 * <p>This product is licensed to you under the Apache License, Version 2.0 (the "License"). You may
 * not use this product except in compliance with the License.
 *
 * <p>This product includes a number of subcomponents with separate copyright notices and license
 * terms. Your use of these subcomponents is subject to the terms and conditions of the
 * subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */
package org.cloudfoundry.identity.uaa.integration;

import org.cloudfoundry.identity.uaa.ServerRunning;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** @author Luke Taylor */
public class PasswordCheckEndpointIntegrationTests {

  @Rule public ServerRunning serverRunning = ServerRunning.isRunning();

  @Test
  public void passwordPostSucceeds() throws Exception {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<String, String>();
    formData.add("password", "password1");
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> response = serverRunning.postForMap("/password/score", formData, headers);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    assertTrue(response.getBody().containsKey("score"));
    assertTrue(response.getBody().containsKey("requiredScore"));
    assertEquals(0, response.getBody().get("score"));
  }

  @Test
  public void passwordPostWithUserDataSucceeds() throws Exception {
    MultiValueMap<String, String> formData = new LinkedMultiValueMap<String, String>();
    formData.add("password", "joe@joesplace.blah");
    formData.add("userData", "joe,joe@joesplace.blah,joesdogsname");
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> response = serverRunning.postForMap("/password/score", formData, headers);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    assertTrue(response.getBody().containsKey("score"));
    assertTrue(response.getBody().containsKey("requiredScore"));
    assertEquals(0, response.getBody().get("score"));
  }
}
