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
package org.cloudfoundry.identity.uaa.resources.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Singleton factory for creating a JdbcPagingList instance with the correct DB specific singleton
 * "Adapter(s)"
 *
 * @author Mike Youngstrom
 */
public class JdbcPagingListFactory {

  private NamedParameterJdbcTemplate jdbcTemplate;
  private LimitSqlAdapter limitSqlAdapter;

  public JdbcPagingListFactory(JdbcTemplate jdbcTemplate, LimitSqlAdapter limitSqlAdapter) {
    this.jdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    this.limitSqlAdapter = limitSqlAdapter;
  }

  public <T> List<T> createJdbcPagingList(
      String sql, Map<String, ?> args, RowMapper<T> mapper, int pageSize) {
    return new JdbcPagingList<T>(jdbcTemplate, limitSqlAdapter, sql, args, mapper, pageSize);
  }
}
