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
package org.cloudfoundry.identity.uaa.authentication.event;

import org.cloudfoundry.identity.uaa.audit.AuditEvent;
import org.cloudfoundry.identity.uaa.audit.AuditEventType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class ClientAuthenticationFailureEvent extends AbstractUaaAuthenticationEvent {

  private String clientId;
  private AuthenticationException ex;

  public ClientAuthenticationFailureEvent(
      Authentication authentication, AuthenticationException ex) {
    super(authentication);
    clientId = getAuthenticationDetails().getClientId();
    this.ex = ex;
  }

  @Override
  public AuditEvent getAuditEvent() {
    return createAuditRecord(
        clientId,
        AuditEventType.ClientAuthenticationFailure,
        getOrigin(getAuthenticationDetails()),
        ex.getMessage());
  }

  public String getClientId() {
    return clientId;
  }
}
