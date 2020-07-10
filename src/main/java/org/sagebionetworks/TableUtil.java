package org.sagebionetworks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;

public class TableUtil {
	private static final int QUERY_PARTS_MASK = 
			SynapseClient.QUERY_PARTMASK |
			SynapseClient.COUNT_PARTMASK |
			SynapseClient.COLUMNS_PARTMASK |
			SynapseClient.MAXROWS_PARTMASK;

	public static final String USER_ID = "UserId";
	public static final String APPLICATION_TEAM_ID = "ApplicationTeamId"; // new field
	public static final String USER_NAME = "User Name";
	public static final String FIRST_NAME = "First Name";
	public static final String LAST_NAME = "Last Name";
	public static final String TOKEN_SENT_DATE = "Date Email Sent";
	public static final String MEMBERSHIP_REQUEST_EXPIRATION_DATE = "Date Membership Request Expires"; // new field
	public static final String APPROVED_ON = "Date Approved/Rejected";
	public static final String DATE_REVOKED = "Date Revoked";

	public static final long TABLE_UPDATE_TIMEOUT = 100000L;

	private SynapseClient synapseClient;
	
	private String tableId;
	private String configurationTableId;

	public TableUtil(SynapseClient synapseClient, String tableId, String configurationTableId) {
		this.synapseClient=synapseClient;
		this.tableId=tableId;
		this.configurationTableId=configurationTableId;
	}
	
	public static List<String> getSubnetsFromString(String s) {
		if (StringUtils.isEmpty(s)) return Collections.EMPTY_LIST;
		return Arrays.asList(s.split("[,;:]"));
	}
	
	/*
	 * return a map whose key is signup team ID and value is the collection of settings for that team
	 */
	public  Map<String,DatasetSettings> getDatasetSettings() throws SynapseException, InterruptedException {
		Pair<List<SelectColumn>, RowSet> queryResult = executeQuery(
				"SELECT * FROM "+configurationTableId, configurationTableId, Integer.MAX_VALUE);
		
		Map<String,DatasetSettings> result = new HashMap<String,DatasetSettings>();

		for (Row row : queryResult.getSecond().getRows()) {
			DatasetSettings setting = new DatasetSettings();
			assert row.getValues().size()==queryResult.getSecond().getHeaders().size();
			for (int i=0; i<queryResult.getSecond().getHeaders().size(); i++) {
				SelectColumn sc = queryResult.getSecond().getHeaders().get(i);
				String value = row.getValues().get(i);
				if (sc.getName().equals("applicationTeamId")) {
					setting.setApplicationTeamId(value);
				} else if (sc.getName().equals("accessRequirementIds")) {
					String[] arIdStrings = value.split(",");
					List<Long> arIds = new ArrayList<Long>();
					for (String arIdString : arIdStrings) arIds.add(Long.parseLong(arIdString));
					setting.setAccessRequirementIds(arIds);
				} else if (sc.getName().equals("tokenLabel")) {
					setting.setTokenLabel(value);
				} else if (sc.getName().equals("dataDescriptor")) {
					setting.setDataDescriptor(value);
				} else if (sc.getName().equals("tokenEmailSynapseId")) {
					setting.setTokenEmailSynapseId(value);
				} else if (sc.getName().equals("approvalEmailSynapseId")) {
					setting.setApprovalEmailSynapseId(value);
				} else if (sc.getName().equals("revocationEmailSynapseId")) {
					setting.setRevocationEmailSynapseId(value);
				} else if (sc.getName().equals("tokenExpirationDays")) {
					setting.setTokenExpirationTimeDays(Integer.parseInt(value));
				} else if (sc.getName().equals("originatingIpSubnet")) {
					List<String> subnets = getSubnetsFromString(value);
					setting.setOriginatingIPsubnets(subnets);
				} else if (sc.getName().equals("expiresAfterDays")) {
					if (!StringUtils.isEmpty(value))
						setting.setExpiresAfterDays(Integer.parseInt(value));
				} else if (sc.getName().equals("approverSynapseIds")) {
					String[] approverSynapseIds = value.split(",");
					setting.setApproverSynapseIds(Arrays.asList(approverSynapseIds));
				} else {
					throw new RuntimeException("Unexpected column "+sc.getName());
				}
			}
			assert setting.getApplicationTeamId()!=null;
			result.put(setting.getApplicationTeamId(), setting);
		}
		return result;
	}
	
	public List<MembershipRequest> getNewMembershipRequests(Collection<MembershipRequest> membershipRequests) throws SynapseException, InterruptedException {
		if (membershipRequests.isEmpty()) return Collections.EMPTY_LIST;
		StringBuilder sb = new StringBuilder("SELECT ");
		sb.append("\""+USER_ID+"\", ");
		sb.append("\""+MEMBERSHIP_REQUEST_EXPIRATION_DATE+"\" ");
		sb.append(" FROM "+tableId+" WHERE "+USER_ID+" IN (");
		boolean firstTime = true;
		String teamId = null;
		for (MembershipRequest mr : membershipRequests) {
			if (teamId==null) {
				teamId = mr.getTeamId();
			} else {
				// all mr's should be for the same teamId
				if (!teamId.equals(mr.getTeamId())) 
					throw new IllegalStateException("Multiple Team IDs: "+teamId+" and "+mr.getTeamId());
			}
			if (firstTime) firstTime=false; else sb.append(",");
			sb.append(mr.getUserId());
		}
		sb.append(")");
		sb.append(" AND \""+APPLICATION_TEAM_ID+"\"='"+teamId+"'");
		String sql = sb.toString();
		Pair<List<SelectColumn>, RowSet> queryResult = executeQuery(sql, tableId, Integer.MAX_VALUE);
		int userIdIndex = getColumnIndexForName(queryResult.getFirst(), USER_ID);
		int expirationIndex = getColumnIndexForName(queryResult.getFirst(), MEMBERSHIP_REQUEST_EXPIRATION_DATE);

		// starting from a list of all membership requests, remove the ones that have already been processed
		List<MembershipRequest> newMembershipRequests = new ArrayList<MembershipRequest>(membershipRequests);
		for (MembershipRequest mr : membershipRequests) {
			String mrUserId = mr.getUserId();
			Date mrExpiresOn = Util.cleanDate(mr.getExpiresOn());
			for (Row row : queryResult.getSecond().getRows()) {
				List<String> values = row.getValues();
				String rowUserId = values.get(userIdIndex);
				String rowExpiresOnString = values.get(expirationIndex);
				Date rowExpiresOn = rowExpiresOnString==null ? null : new Date(Long.parseLong(rowExpiresOnString));
				if (mrUserId.equals(rowUserId) && Util.datesEqualNoMillis(rowExpiresOn, mrExpiresOn)) {
					newMembershipRequests.remove(mr);
					break;
				}
			}
		}
		return newMembershipRequests;
	}
	
	/*
	 * Given a list of tcs, return the rows for those that have not yet been approved
	 */
	public TokenTableLookupResults getRowsForAcceptedButNotYetApprovedUserIds(Collection<TokenContent> tcs) throws SynapseException, InterruptedException {
		TokenTableLookupResults result = new TokenTableLookupResults();
		RowSet rowSet = new RowSet();
		List<Row> rows = new ArrayList<Row>();
		rowSet.setRows(rows);
		result.setRowSet(rowSet);
		if (tcs.isEmpty())  return result;
		StringBuilder sb = new StringBuilder("SELECT * FROM ");
		sb.append(tableId+" WHERE "+USER_ID+" IN (");
		boolean firstTime = true;
		for (TokenContent tc : tcs) {
			if (firstTime) firstTime=false; else sb.append(",");
			sb.append(tc.getUserId());
		}
		sb.append(") AND \"");
		sb.append(APPROVED_ON);
		sb.append("\" IS NULL");
		String sql = sb.toString();
		Pair<List<SelectColumn>, RowSet> queryResult = executeQuery(sql, tableId, Integer.MAX_VALUE);
		int userIdIndex = getColumnIndexForName(queryResult.getFirst(), USER_ID);
		int teamIdIndex = getColumnIndexForName(queryResult.getFirst(), APPLICATION_TEAM_ID);
		int expirationIndex = getColumnIndexForName(queryResult.getFirst(), MEMBERSHIP_REQUEST_EXPIRATION_DATE);
		
		for (TokenContent tc : tcs) {
			String userId = ""+tc.getUserId();
			String teamId = tc.getApplicationTeamId();
			Date mrExpiration = tc.getMembershipRequestExpiration();
			for (Row row : queryResult.getSecond().getRows()) {
				List<String> values = row.getValues();
				String rowUser = values.get(userIdIndex);
				String rowTeam = values.get(teamIdIndex);
				String rowExpiration = values.get(expirationIndex);
				boolean datesMatch = rowExpiration==null ? mrExpiration==null :
					Util.datesEqualNoMillis(new Date(Long.parseLong(rowExpiration)), mrExpiration);
				if (userId.equals(rowUser) && StringUtils.equals(teamId, rowTeam) && datesMatch) {
					// found it!
					
					// make a new row and add it to the result that we return
					// we copy the data from the query result to ensure that it's a
					// mutable object
					Row rowCopy = new Row();
					rowCopy.setRowId(row.getRowId());
					rowCopy.setVersionNumber(row.getVersionNumber());
					rowCopy.setValues(new ArrayList<String>(values));
					rows.add(rowCopy);
					
					result.addToken(tc);
				}			
			}
		}

		rowSet.setEtag(queryResult.getSecond().getEtag());
		rowSet.setHeaders(queryResult.getFirst());
		rowSet.setTableId(tableId);
		return result;
	}
	
	private static final long MILLIS_PER_DAY = 1000*3600*24;
	
	// query for users whose approval date is more than the given days prior to the present day
	public Pair<List<SelectColumn>, RowSet> getExpiredAccess(DatasetSettings ds) throws Exception {
			if (ds.getExpiresAfterDays()==null) throw new IllegalArgumentException();
			long expirationThreshold = System.currentTimeMillis() - ds.getExpiresAfterDays()*MILLIS_PER_DAY;

			StringBuilder sb = new StringBuilder("SELECT \""+USER_ID+"\",\""+DATE_REVOKED+"\" FROM ");
			sb.append(tableId);
			sb.append(" WHERE \"");
			sb.append(APPLICATION_TEAM_ID);
			sb.append("\" = ");
			sb.append(ds.getApplicationTeamId());
			sb.append(" and \"");
			sb.append(APPROVED_ON);
			sb.append("\"<");
			sb.append(expirationThreshold);
			sb.append(" and \"");
			sb.append(DATE_REVOKED);
			sb.append("\" is null");
			String sql = sb.toString();
			return executeQuery(sql, tableId, Integer.MAX_VALUE);
	}

	/*
	 * Executes a query for which the max number of returned rows is known (i.e. we retrieve in a single page)
	 */
	private Pair<List<SelectColumn>, RowSet> executeQuery(String sql, String tableId, long queryLimit) throws SynapseException, InterruptedException {
		String asyncJobToken = synapseClient.queryTableEntityBundleAsyncStart(sql, 0L, queryLimit, QUERY_PARTS_MASK, tableId);
		QueryResultBundle qrb=null;
		long backoff = 100L;
		for (int i=0; i<100; i++) {
			try {
				qrb = synapseClient.queryTableEntityBundleAsyncGet(asyncJobToken, tableId);
				break;
			} catch (SynapseResultNotReadyException e) {
				// keep waiting
				Thread.sleep(backoff);
				backoff *=2L;
			}
		}
		if (qrb==null) throw new RuntimeException("Query failed to return");
		List<Row> rows = qrb.getQueryResult().getQueryResults().getRows();
		if (qrb.getQueryCount()>rows.size()) throw new IllegalStateException(
				"Queried for "+queryLimit+" users but got back "+ rows.size()+" and total count: "+qrb.getQueryCount());
		return new Pair<List<SelectColumn>, RowSet>(qrb.getSelectColumns(), qrb.getQueryResult().getQueryResults());
	}


	/*
	 * returns a list of SelectColumns for the given columnNames in the same order as
	 * said columnNames.
	 */
	public List<SelectColumn> createRowSetHeaders(String tableId, String[] columnNames) throws SynapseException  {
		List<SelectColumn> result = new ArrayList<SelectColumn>();
		List<ColumnModel> columns = synapseClient.getColumnModelsForTableEntity(tableId);
		for (String columnName : columnNames) {
			for (ColumnModel column : columns) {
				if (column.getName().equals(columnName)) {
					SelectColumn sc = new SelectColumn();
					sc.setColumnType(column.getColumnType());
					sc.setId(column.getId());
					sc.setName(columnName);
					result.add(sc);
					break;
				}
			}
		}
		if (result.size()<columnNames.length) throw new RuntimeException("Could not find columns for all column names.");
		return result;
	}

	public static int getColumnIndexForName(List<SelectColumn> columns, String name)  {
		for (int i=0; i<columns.size(); i++) {
			if (columns.get(i).getName().equals(name)) return i;
		}
		List<String> names = new ArrayList<String>();
		for (SelectColumn column : columns) names.add(column.getName());
		throw new IllegalArgumentException("No column named "+name+". Available names: "+names);
	}
	

}
