package org.sagebionetworks;

import static org.sagebionetworks.Util.getProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.MembershipRequest;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.SelectColumn;

public class TableUtil {
	private static final int QUERY_PARTS_MASK = 
			SynapseClient.QUERY_PARTMASK |
			SynapseClient.COUNT_PARTMASK |
			SynapseClient.COLUMNS_PARTMASK |
			SynapseClient.MAXROWS_PARTMASK;

	private static final int COLUMN_COUNT = 10;
	public static final String USER_ID = "UserId";
	public static final String APPLICATION_TEAM_ID = "ApplicationTeamId"; // new field
	public static final String USER_NAME = "User Name";
	public static final String FIRST_NAME = "First Name";
	public static final String LAST_NAME = "Last Name";
	public static final String TOKEN_SENT_DATE = "Date Email Sent";
	public static final String MEMBERSHIP_REQUEST_EXPIRATION_DATE = "Date Membership Request Expires"; // new field
	public static final String APPROVED_OR_REJECTED_DATE = "Date Approved/Rejected";
	public static final String APPROVED = "Approved";
	public static final String REASON_REJECTED = "Reason Rejected";

	public static final long TABLE_UPDATE_TIMEOOUT = 10000L;

	private SynapseClient synapseClient;
	

	public TableUtil(SynapseClient synapseClient) {
		this.synapseClient=synapseClient;
	}
	
	public List<MembershipRequest> getNewMembershipRequests(Collection<MembershipRequest> membershipRequests) throws SynapseException, InterruptedException {
		if (membershipRequests.isEmpty()) return Collections.EMPTY_LIST;
		String tableId = getProperty("TABLE_ID");
		StringBuilder sb = new StringBuilder("SELECT ");
		sb.append("\""+USER_ID+"\", ");
		sb.append("\""+MEMBERSHIP_REQUEST_EXPIRATION_DATE+"\", ");
		sb.append(" FROM "+tableId+" WHERE "+USER_ID+" IN (");
		boolean firstTime = true;
		String teamId = null;
		for (MembershipRequest mr : membershipRequests) {
			if (teamId==null) {
				teamId = mr.getTeamId();
			} else {
				// all mr's should be for the same teamId
				if (teamId!=mr.getTeamId()) 
					throw new IllegalStateException("Multiple Team IDs: "+teamId+" and "+mr.getTeamId());
			}
			if (firstTime) firstTime=false; else sb.append(",");
			sb.append(mr.getUserId());
		}
		sb.append(")");
		sb.append(" AND \""+APPLICATION_TEAM_ID+"\"='"+teamId+"'");
		String sql = sb.toString();
		Pair<List<SelectColumn>, List<Row>> queryResult = executeQuery(sql, tableId, Integer.MAX_VALUE);
		int userIdIndex = getColumnIndexForName(queryResult.getFirst(), USER_ID);
		int expirationIndex = getColumnIndexForName(queryResult.getFirst(), MEMBERSHIP_REQUEST_EXPIRATION_DATE);

		// starting from a list of all membership requests, remove the ones that have already been processed
		List<MembershipRequest> newMembershipRequests = new ArrayList<MembershipRequest>(membershipRequests);
		for (MembershipRequest mr : membershipRequests) {
			for (Row row : queryResult.getSecond()) {
				List<String> values = row.getValues();
				if (mr.getUserId().equals(values.get(userIdIndex)) &&
						values.get(expirationIndex)!=null && 
						mr.getExpiresOn().equals(new Date(Long.parseLong(values.get(expirationIndex))))) {
					newMembershipRequests.remove(mr);
					break;
				}
			}
		}
		return newMembershipRequests;
	}

	/*
	 * Executes a query for which the max number of returned rows is known (i.e. we retrieve in a single page)
	 */
	private Pair<List<SelectColumn>, List<Row>> executeQuery(String sql, String tableId, long queryLimit) throws SynapseException, InterruptedException {
		String asyncJobToken = synapseClient.queryTableEntityBundleAsyncStart(sql, 0L, queryLimit, true, QUERY_PARTS_MASK, tableId);
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
		return new Pair<List<SelectColumn>, List<Row>>(qrb.getSelectColumns(), rows);
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

	public static SelectColumn getColumnForName(List<SelectColumn> columns, String name)  {
		for (SelectColumn column : columns) {
			if (column.getName().equals(name)) return column;
		}
		throw new IllegalArgumentException("No column named "+name);
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
