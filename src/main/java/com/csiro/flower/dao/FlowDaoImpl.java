/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.csiro.flower.dao;

import com.csiro.flower.model.Flow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 *
 * @author kho01f
 */
@Repository
public class FlowDaoImpl implements FlowDao {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Flow> getAll(String user) {
        String sqlSelect = "SELECT * FROM flow_tbl WHERE flow_owner='" + user + "'";
        return jdbcTemplate.query(sqlSelect, new BeanPropertyRowMapper(Flow.class));
    }

    @Override
    public int save(final Flow flow) {
        final String sqlInsert = "INSERT INTO flow_tbl (flow_name, flow_owner, "
                + "platforms, creation_date) VALUES (?,?,?,?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(new PreparedStatementCreator() {

            @Override
            public PreparedStatement createPreparedStatement(Connection cnctn) throws SQLException {
                PreparedStatement ps = cnctn.prepareStatement(sqlInsert, new String[]{"flow_id"});
                ps.setString(1, flow.getFlowName());
                ps.setString(2, flow.getFlowOwner());
                ps.setString(3, flow.getPlatforms());
                ps.setTimestamp(4, flow.getCreationDate());
                return ps;
            }
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    @Override
    public void delete(int flowId) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Flow get(int flowId) {
        String sqlSelect = "SELECT * FROM flow_tbl WHERE flow_id=" + flowId;
        return jdbcTemplate.queryForObject(sqlSelect, new RowMapper<Flow>() {
            @Override
            public Flow mapRow(ResultSet result, int rowNum) throws SQLException {
                Flow flow = new Flow();
                flow.setFlowName(result.getString("flow_name"));
                flow.setFlowOwner(result.getString("flow_owner"));
                flow.setPlatforms(result.getString("platforms"));
                flow.setCreationDate(result.getTimestamp("creation_date"));
                return flow;
            }
        });
    }

    @Override
    public void update(Flow flow) {

    }

}
