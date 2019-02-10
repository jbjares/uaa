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
import org.springframework.http.ResponseEntity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** @author Dave Syer */
public class HealthzEndpointIntegrationTests {

  @Rule public ServerRunning serverRunning = ServerRunning.isRunning();

  /** tests a happy-day flow of the <code>/healthz</code> endpoint */
  @Test
  public void testHappyDay() throws Exception {

    HttpHeaders headers = new HttpHeaders();
    ResponseEntity<String> response = serverRunning.getForString("/healthz/", headers);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    String body = response.getBody();
    assertTrue(body.contains("ok"));
  }
}
