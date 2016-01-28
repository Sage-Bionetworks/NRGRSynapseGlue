package org.sagebionetworks;

import java.util.HashSet;
import java.util.Set;

import org.sagebionetworks.repo.model.table.RowSet;

/*
 * This holds the results of looking up a set of tokens in the table
 */
public class TokenTableLookupResults {
	private Set<TokenContent> tokens;
	private RowSet rowSet;

	public TokenTableLookupResults() {
		tokens = new HashSet<TokenContent>();
	}

	public Set<TokenContent> getTokens() {
		return tokens;
	}
	
	public void addToken(TokenContent token) {
		this.tokens.add(token);
	}

	public RowSet getRowSet() {
		return rowSet;
	}

	public void setRowSet(RowSet rowSet) {
		this.rowSet = rowSet;
	}
	
	

}
