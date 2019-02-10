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
package org.cloudfoundry.identity.uaa.resources;

import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** @author Dave Syer */
public class MessageTests {

  @Test
  public void testSerialize() throws Exception {
    assertEquals(
        "{\"status\":\"ok\",\"message\":\"done\"}",
        JsonUtils.writeValueAsString(new ActionResult("ok", "done")));
  }

  @Test
  public void testDeserialize() throws Exception {
    String value = "{\"status\":\"ok\",\"message\":\"done\"}";
    ActionResult message = JsonUtils.readValue(value, ActionResult.class);
    assertEquals(new ActionResult("ok", "done"), message);
  }
}
