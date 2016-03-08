package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.sagebionetworks.TableUtil.APPLICATION_TEAM_ID;
import static org.sagebionetworks.TableUtil.APPROVED_ON;
import static org.sagebionetworks.TableUtil.FIRST_NAME;
import static org.sagebionetworks.TableUtil.LAST_NAME;
import static org.sagebionetworks.TableUtil.MEMBERSHIP_REQUEST_EXPIRATION_DATE;
import static org.sagebionetworks.TableUtil.TABLE_UPDATE_TIMEOUT;
import static org.sagebionetworks.TableUtil.TOKEN_SENT_DATE;
import static org.sagebionetworks.TableUtil.USER_ID;
import static org.sagebionetworks.TableUtil.USER_NAME;
import static org.sagebionetworks.Util.getProperty;
import static org.sagebionetworks.repo.model.table.ColumnType.INTEGER;
import static org.sagebionetworks.repo.model.table.ColumnType.STRING;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.TableEntity;

public class TableUtilTest {
	
    private Project project;
	private SynapseClient synapseClient;
	private TableEntity table;
	private TableUtil tableUtil;
	
	public static ColumnModel createColumn(String name, ColumnType columnType) {
		ColumnModel c = new ColumnModel();
		c.setName(name);
		c.setColumnType(columnType);
		return c;
	}
	
	@Before
	public void setUp() throws Exception {
		synapseClient = Util.createSynapseClient();
		String adminUserName = getProperty("USERNAME");
		String adminPassword = getProperty("PASSWORD");
		synapseClient.login(adminUserName, adminPassword);
		
    	project = new Project();
    	project.setName(UUID.randomUUID().toString());
    	project = synapseClient.createEntity(project);
    	
    	List<ColumnModel> columns = new ArrayList<ColumnModel>();
    	columns.add(createColumn(USER_ID, ColumnType.INTEGER));
    	columns.add(createColumn(APPLICATION_TEAM_ID, ColumnType.INTEGER));
    	columns.add(createColumn(USER_NAME, ColumnType.STRING));
    	columns.add(createColumn(FIRST_NAME, ColumnType.STRING));
    	columns.add(createColumn(LAST_NAME, ColumnType.STRING));
    	columns.add(createColumn(TOKEN_SENT_DATE, ColumnType.DATE));
       	columns.add(createColumn(MEMBERSHIP_REQUEST_EXPIRATION_DATE, ColumnType.DATE));
       	columns.add(createColumn(APPROVED_ON, ColumnType.DATE));
        columns = synapseClient.createColumnModels(columns);
    	List<String> columnIds = new ArrayList<String>();
    	for (ColumnModel column : columns) columnIds.add(column.getId());
		table = new TableEntity();
		table.setName(UUID.randomUUID().toString());
		table.setColumnIds(columnIds);
		table.setParentId(project.getId());
		table = synapseClient.createEntity(table);
		
		tableUtil = new TableUtil(synapseClient, table.getId());
	}
	
	@After
	public void teardown() throws Exception {
    	if (project!=null) {
    		synapseClient.deleteEntityById(project.getId());
    		project=null;
    	}
	}
	
	@Test
	public void testCreateRowSetHeaders() throws Exception {
		String[] columnNames = new String[] {USER_ID, USER_NAME};
		ColumnType[] columnTypes = new ColumnType[]{INTEGER, STRING};
		List<SelectColumn> selectColumns = tableUtil.createRowSetHeaders(table.getId(), columnNames);
		assertEquals(2, selectColumns.size());
		for (int i=0; i<columnNames.length; i++) {
			SelectColumn sc = selectColumns.get(i);
			assertEquals(columnTypes[i], sc.getColumnType());
			assertNotNull(sc.getId());
			assertEquals(columnNames[i], sc.getName());
		}
	}

	@Test
	public void testGetNewMembershipRequests() throws Exception {
		// add rows
		String[] columnNames = new String[] {USER_ID, APPLICATION_TEAM_ID, MEMBERSHIP_REQUEST_EXPIRATION_DATE};
		List<SelectColumn> headers = tableUtil.createRowSetHeaders(table.getId(), columnNames);
		RowSet rowSet = new RowSet();
		List<Row> rows = new ArrayList<Row>();
		rowSet.setHeaders(headers);
		rowSet.setTableId(table.getId());
		rowSet.setRows(rows);
		Row row;
		Long atime = System.currentTimeMillis();
		// add a user for the team of interest
		row = new Row(); row.setValues(Arrays.asList("111", "999", atime.toString())); rows.add(row);
		// add a user for another team
		row = new Row(); row.setValues(Arrays.asList("222", "888", atime.toString())); rows.add(row);
		// add a user with no team
		row = new Row(); row.setValues(Arrays.asList("333", null, atime.toString())); rows.add(row);
		// make sure a legacy entry that doesn't have a teamId or expiration date doesn't cause a problem
		row = new Row(); row.setValues(Arrays.asList("111", null, null)); rows.add(row);
		synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, table.getId());
		
		// if the MR is already in the table, then it is not returned
		MembershipRequest mr = new MembershipRequest();
		mr.setUserId("111");
		mr.setTeamId("999");
		mr.setExpiresOn(new Date(atime));
		List<MembershipRequest> newMRs = tableUtil.getNewMembershipRequests(Collections.singletonList(mr));
		assertTrue(newMRs.isEmpty());
		
		// but if an MR for another TEAM is in table, the request IS returned
		mr = new MembershipRequest();
		mr.setUserId("111");
		mr.setTeamId("000");
		mr.setExpiresOn(new Date(atime));
		newMRs = tableUtil.getNewMembershipRequests(Collections.singletonList(mr));
		assertEquals(1, newMRs.size());
		assertEquals(mr, newMRs.get(0));
		
		// OR if an MR for another USER is in table, the request IS returned
		mr = new MembershipRequest();
		mr.setUserId("000");
		mr.setTeamId("999");
		mr.setExpiresOn(new Date(atime));
		newMRs = tableUtil.getNewMembershipRequests(Collections.singletonList(mr));
		assertEquals(1, newMRs.size());
		assertEquals(mr, newMRs.get(0));
		
		// OR if an MR for another TIME is in table, the request IS returned
		mr = new MembershipRequest();
		mr.setUserId("111");
		mr.setTeamId("999");
		mr.setExpiresOn(new Date(atime+1000));
		newMRs = tableUtil.getNewMembershipRequests(Collections.singletonList(mr));
		assertEquals(1, newMRs.size());
		assertEquals(mr, newMRs.get(0));
	}
	
	@Test
	public void testGetRowsForAcceptedButNotYetApprovedUserIds() throws Exception {
		// add rows
		String[] columnNames = new String[] {USER_ID, APPLICATION_TEAM_ID, MEMBERSHIP_REQUEST_EXPIRATION_DATE, APPROVED_ON};
		List<SelectColumn> headers = tableUtil.createRowSetHeaders(table.getId(), columnNames);
		RowSet rowSet = new RowSet();
		List<Row> rows = new ArrayList<Row>();
		rowSet.setHeaders(headers);
		rowSet.setTableId(table.getId());
		rowSet.setRows(rows);
		Row row;
		Long atime = System.currentTimeMillis();
		// add a user for the team of interest (already approved)
		row = new Row(); row.setValues(Arrays.asList("111", "999", atime.toString(), atime.toString())); rows.add(row);
		// add a user for another team (not yet approved)
		row = new Row(); row.setValues(Arrays.asList("222", "888", atime.toString(), null)); rows.add(row);
		// add a user with no team (not yet approved)
		row = new Row(); row.setValues(Arrays.asList("333", null, atime.toString(), null)); rows.add(row);
		// make sure a legacy entry that doesn't have a teamId or expiration date doesn't cause a problem
		row = new Row(); row.setValues(Arrays.asList("111", null, null, atime.toString())); rows.add(row);
		synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, table.getId());
		
		Collection<TokenContent> tcs;
		
		// first check what happens when no tokens are passed in
		tcs = new ArrayList<TokenContent>();
		TokenTableLookupResults ttlr = tableUtil.getRowsForAcceptedButNotYetApprovedUserIds(tcs);
		assertTrue(ttlr.getTokens().isEmpty());
		assertTrue(ttlr.getRowSet().getRows().isEmpty());
		
		// pass in a token which has already been approved
		tcs = new ArrayList<TokenContent>();
		TokenContent tc = new TokenContent(111L, null, null, null, "999", new Date(atime));
		tcs.add(tc);
		ttlr = tableUtil.getRowsForAcceptedButNotYetApprovedUserIds(tcs);
		assertTrue(ttlr.getTokens().isEmpty());
		assertTrue(ttlr.getRowSet().getRows().isEmpty());
		
		// pass in a token for a request which has NOT YET been approved
		tcs = new ArrayList<TokenContent>();
		tc = new TokenContent(222L, null, null, null, "888", new Date(atime));
		tcs.add(tc);
		ttlr = tableUtil.getRowsForAcceptedButNotYetApprovedUserIds(tcs);
		assertEquals(1, ttlr.getTokens().size());
		assertEquals(tc, ttlr.getTokens().iterator().next());
		assertEquals(1, ttlr.getRowSet().getRows().size());
		List<String> rowValues = ttlr.getRowSet().getRows().get(0).getValues();
		int userIdIndex = TableUtil.getColumnIndexForName(ttlr.getRowSet().getHeaders(), USER_ID);
		int teamIdIndex = TableUtil.getColumnIndexForName(ttlr.getRowSet().getHeaders(), APPLICATION_TEAM_ID);
		int expirationIndex = TableUtil.getColumnIndexForName(ttlr.getRowSet().getHeaders(), MEMBERSHIP_REQUEST_EXPIRATION_DATE);
		assertEquals("222", rowValues.get(userIdIndex));
		assertEquals("888", rowValues.get(teamIdIndex));
		assertEquals(atime.toString(), rowValues.get(expirationIndex));
		
		// add a row for our user with no team ID and no rm expiration
		// this is the condition for a legacy table entry.  It should match
		// a token which has null values for team ID and rm
		row = new Row(); row.setValues(Arrays.asList("111", null, null, null)); rows.add(row);
		rows = Collections.singletonList(row);
		rowSet = new RowSet();
		rowSet.setHeaders(headers);
		rowSet.setRows(rows);
		rowSet.setTableId(table.getId());
		synapseClient.appendRowsToTable(rowSet, TABLE_UPDATE_TIMEOUT, table.getId());
		
		tcs = new ArrayList<TokenContent>();
		tc = new TokenContent(111L, null, null, null, null, null);
		tcs.add(tc);
		ttlr = tableUtil.getRowsForAcceptedButNotYetApprovedUserIds(tcs);

		assertEquals(1, ttlr.getTokens().size());
		assertEquals(tc, ttlr.getTokens().iterator().next());
		assertEquals(1, ttlr.getRowSet().getRows().size());
		rowValues = ttlr.getRowSet().getRows().get(0).getValues();
		assertEquals("111", rowValues.get(userIdIndex));
		assertEquals(null, rowValues.get(teamIdIndex));
		assertEquals(null, rowValues.get(expirationIndex));
	}
	
	@Test
	public void testGetColumnIndexForName() throws Exception {
		String[] columnNames = new String[] {USER_ID, USER_NAME};
		List<SelectColumn> selectColumns = tableUtil.createRowSetHeaders(table.getId(), columnNames);
		assertEquals(0, TableUtil.getColumnIndexForName(selectColumns, USER_ID));
		assertEquals(1, TableUtil.getColumnIndexForName(selectColumns, USER_NAME));
	}


}
