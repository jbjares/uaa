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
package org.cloudfoundry.identity.uaa.provider.saml;

import org.opensaml.saml2.metadata.provider.MetadataProviderException;

import java.io.File;
import java.util.Timer;

public class FilesystemMetadataProvider
    extends org.opensaml.saml2.metadata.provider.FilesystemMetadataProvider {

  public FilesystemMetadataProvider(Timer backgroundTaskTimer, File metadata)
      throws MetadataProviderException {
    super(backgroundTaskTimer, metadata);
  }

  @Override
  public byte[] fetchMetadata() throws MetadataProviderException {
    return super.fetchMetadata();
  }
}
