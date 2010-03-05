/**
 * 
 */
package com.akiban.cserver.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.akiban.cserver.CServerUtil;
import com.akiban.cserver.IndexDef;
import com.akiban.cserver.MySQLErrorConstants;
import com.akiban.cserver.RowData;
import com.akiban.cserver.RowDef;
import com.akiban.cserver.RowType;
import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.KeyFilter;

public class PersistitStoreRowCollector implements RowCollector,
		MySQLErrorConstants {

	static final Log LOG = LogFactory.getLog(PersistitStoreRowCollector.class
			.getName());

	private final static int INITIAL_BUFFER_SIZE = 1024;

	private final PersistitStore store;

	private final byte[] columnBitMap;

	private int columnBitMapOffset;

	private int columnBitMapWidth;

	private boolean ascending = true;

	private RowDef groupRowDef;

	private IndexDef indexDef;

	private int leafRowDefId;

	private KeyFilter iFilter;

	private KeyFilter hFilter;

	private boolean more = true;

	private RowDef[] projectedRowDefs;

	private RowData[] pendingRowData;

	private int pendingFromLevel = Integer.MAX_VALUE;

	private int pendingToLevel;

	private int indexPinnedKeySize;

	private boolean traverseMode;

	private Exchange iEx;

	private Exchange hEx;

	private Key lastKey;

	private Key.Direction direction;

	PersistitStoreRowCollector(PersistitStore store, final RowData start,
			final RowData end, final byte[] columnBitMap, RowDef rowDef,
			final int indexId) throws Exception {
		this.store = store;
		this.columnBitMap = columnBitMap;
		this.columnBitMapOffset = rowDef.getColumnOffset();
		this.columnBitMapWidth = rowDef.getFieldCount();

		if (rowDef.isGroupTable()) {
			this.groupRowDef = rowDef;
		} else {
			this.groupRowDef = store.getRowDefCache().getRowDef(
					rowDef.getGroupRowDefId());
		}

		this.projectedRowDefs = computeProjectedRowDefs(rowDef,
				this.groupRowDef, columnBitMap);
		if (this.projectedRowDefs.length == 0) {
			this.more = false;
		} else {
			this.pendingRowData = new RowData[this.projectedRowDefs.length];

			this.hEx = store.getExchange(rowDef, null);
			this.hFilter = computeHFilter(rowDef, start, end);
			this.lastKey = new Key(hEx.getKey());

			if (indexId != 0) {
				final IndexDef def = rowDef.getIndexDef(indexId);
				if (!def.isHKeyEquivalent()) {
					this.indexDef = def;
					// Don't use the primary key index for ROOT tables - the
					// index
					// tree is not populated because it is redundant with the
					// h-tree itself.
					this.iEx = store.getExchange(rowDef, indexDef);
					this.iFilter = computeIFilter(indexDef, rowDef, start, end);
					if (store.isVerbose() && LOG.isInfoEnabled()) {
						LOG.info("Select using index " + indexDef + " filter=" + iFilter);
					}
				}
			}

			for (int level = 0; level < pendingRowData.length; level++) {
				pendingRowData[level] = new RowData(
						new byte[INITIAL_BUFFER_SIZE]);
			}

			this.pendingFromLevel = Integer.MAX_VALUE;
			this.pendingToLevel = 0;
			this.direction = ascending ? Key.GTEQ : Key.LTEQ;
		}

		if (LOG.isTraceEnabled()) {
			LOG.trace("Starting Scan on rowDef=" + rowDef.toString()
					+ ": leafRowDefId=" + leafRowDefId);
		}
	}

	RowDef[] computeProjectedRowDefs(final RowDef rowDef,
			final RowDef groupRowDef, final byte[] columnBitMap) {
		final int columnOffset = rowDef.getColumnOffset();
		final int columnCount = rowDef.getFieldCount();

		boolean isEmpty = true;
		for (int index = 0; index < columnBitMap.length; index++) {
			if (columnBitMap[index] != 0) {
				isEmpty = false;
				break;
			}
		}
		// Handles special case of SELECT COUNT(*)
		if (isEmpty) {
			return new RowDef[] { rowDef };
		}

		List<RowDef> projectedRowDefList = new ArrayList<RowDef>();
		for (int index = 0; index < groupRowDef.getUserTableRowDefs().length; index++) {
			final RowDef def = groupRowDef.getUserTableRowDefs()[index];

			int from = def.getColumnOffset();
			int width = def.getFieldCount();
			from -= columnOffset;
			if (from < 0) {
				width += from;
				from = 0;
			}
			if (width > columnCount) {
				width = columnCount;
			}
			if (from + width > columnBitMap.length * 8) {
				width = columnBitMap.length * 8 - from;
			}

			boolean projected = false;
			for (int bit = from; !projected && bit < from + width; bit++) {
				if (isBit(columnBitMap, bit)) {
					projected = true;
				}
			}
			if (projected) {
				projectedRowDefList.add(def);
			}
		}
		return projectedRowDefList.toArray(new RowDef[projectedRowDefList
				.size()]);
	}

	/**
	 * Construct a KeyFilter on the h-key. This minimally contains a template
	 * for the ordinal tree identifiers. If any of the supplied index range
	 * values pertain to primary key fields, these will also be constrained.
	 * 
	 * @param rowDef
	 * @param start
	 * @param end
	 * @return
	 * @throws Exception
	 */
	KeyFilter computeHFilter(final RowDef rowDef, final RowData start,
			final RowData end) throws Exception {
		final RowDef leafRowDef = projectedRowDefs[projectedRowDefs.length - 1];
		final KeyFilter.Term[] terms = new KeyFilter.Term[leafRowDef
				.getHKeyDepth()];
		final Key key = hEx.getKey();
		int index = terms.length;
		RowDef def = leafRowDef;

		while (def != null) {
			final int[] fields = def.getPkFields();
			if (index < (fields.length + 1)) {
				throw new IllegalStateException(
						"Length mismatch in computeHFilter: def=" + def
								+ " leafRowDef=" + leafRowDef + " index="
								+ index);
			}
			terms[index - fields.length - 1] = KeyFilter.simpleTerm(def
					.getOrdinal());
			for (int k = 0; k < fields.length; k++) {
				terms[index - fields.length + k] = computeKeyFilterTerm(key,
						rowDef, start, end, fields[k]);
			}
			index -= (fields.length + 1);
			final int parentId = def.getParentRowDefId();
			def = parentId == 0 ? null : store.getRowDefCache().getRowDef(
					parentId);
		}
		if (index != 0) {
			throw new IllegalStateException(
					"Length mismatch in computeHFilter: leafRowDef="
							+ leafRowDef + " index=" + index);
		}
		key.clear();
		return new KeyFilter(terms, 0, terms.length);
	}

	/**
	 * Construct a key filter on the index key
	 * 
	 * @param indexDef
	 * @param rowDef
	 * @param start
	 * @param end
	 * @return
	 */
	KeyFilter computeIFilter(final IndexDef indexDef, final RowDef rowDef,
			final RowData start, final RowData end) {
		final Key key = iEx.getKey();
		final int[] fields = indexDef.getFields();
		final KeyFilter.Term[] terms = new KeyFilter.Term[fields.length];
		for (int index = 0; index < fields.length; index++) {
			terms[index] = computeKeyFilterTerm(key, rowDef, start, end,
					fields[index]);
		}
		key.clear();
		return new KeyFilter(terms, terms.length, Integer.MAX_VALUE);
	}

	/**
	 * Returns a KeyFilter term if the specified field of either the start or
	 * end RowData is non-null, else null.
	 * 
	 * @param key
	 * @param rowDef
	 * @param start
	 * @param end
	 * @param fieldIndex
	 * @return
	 */
	KeyFilter.Term computeKeyFilterTerm(final Key key, final RowDef rowDef,
			final RowData start, final RowData end, final int fieldIndex) {
		final long lowLoc = rowDef.fieldLocation(start, fieldIndex);
		final long highLoc = rowDef.fieldLocation(end, fieldIndex);
		if (lowLoc != 0 || highLoc != 0) {
			key.clear();
			key.reset();
			if (lowLoc != 0) {
				store.appendKeyField(key, rowDef.getFieldDef(fieldIndex),
						start, lowLoc);
			} else {
				key.append(Key.BEFORE);
			}
			if (highLoc != 0) {
				store.appendKeyField(key, rowDef.getFieldDef(fieldIndex), end,
						highLoc);
			} else {
				key.append(Key.AFTER);
			}
			//
			// Tricky: termFromKeySegments reads successive key segments when
			// called this way.
			//
			return KeyFilter.termFromKeySegments(key, key, true, true);
		} else {
			return KeyFilter.ALL;
		}
	}

	/**
	 * Test for bit set in a column bit map
	 * 
	 * @param columnBitMap
	 * @param column
	 * @return <tt>true</tt> if the bit for the specified column in the bit map
	 *         is 1
	 */
	boolean isBit(final byte[] columnBitMap, final int column) {
		if (columnBitMap == null) {
			if (LOG.isErrorEnabled()) {
				LOG.error("ColumnBitMap is null in ScanRowsRequest on table "
						+ groupRowDef);
			}
			return false;
		}
		if ((column / 8) >= columnBitMap.length || column < 0) {
			if (LOG.isErrorEnabled()) {
				LOG.error("ColumnBitMap is too short in "
						+ "ScanRowsRequest on table " + groupRowDef
						+ " columnBitMap has " + columnBitMap.length
						+ " bytes, but isBit is " + "trying to test bit "
						+ column);
			}
			return false;
		}
		return (columnBitMap[column / 8] & (1 << (column % 8))) != 0;
	}

	/**
	 * Selects the next row in tree-traversal order and puts it into the payload
	 * ByteBuffer if there is room. Returns <tt>true</tt> if and only if a row
	 * was added to the payload ByteBuffer. As a side-effect, this method
	 * affects the value of <tt>more</tt> to indicate whether there are
	 * additional rows in the tree; {@link #hasMore()} returns this value.
	 * 
	 */
	@Override
	public boolean collectNextRow(ByteBuffer payload) throws Exception {
		int available = payload.limit() - payload.position();
		if (available < 4) {
			throw new IllegalStateException(
					"Payload byte buffer must have at least 4 "
							+ "available bytes: " + available);
		}
		if (!more) {
			return false;
		}

		final Key hKey = hEx.getKey();

		if (indexDef == null) {
			traverseMode = true;
		}

		while (true) {

			//
			// Flush any available pending rows. A row on in the pendingRowData
			// array is available only if the leaf level of the array has been
			// populated.
			//
			if (pendingFromLevel < pendingToLevel
					&& pendingToLevel == pendingRowData.length) {
				if (deliverRow(pendingRowData[pendingFromLevel], payload)) {
					pendingFromLevel++;
					return true;
				} else {
					return false;
				}
			}

			if (!traverseMode) {
				pendingToLevel = 0;
				//
				// Traverse to next key in Index
				//
				more = iEx.traverse(direction, iFilter, 0);
				if (!more) {
					//
					// All done
					//
					return false;
				}
				direction = ascending ? Key.GT : Key.LT;
				store.constructHKeyFromIndexKey(hKey, iEx.getKey(), indexDef);

				final int differsAt = hKey.firstUniqueByteIndex(lastKey);
				final int depth = hKey.getDepth();
				indexPinnedKeySize = hKey.getEncodedSize();

				//
				// Back-fill all needed ancestor rows. An ancestor row is needed
				// if it is in the projection, and if the hKey of the current
				// tree traversal location differs from that of the previously
				// prepared row at a key segment left of the ancestor row's key
				// depth.
				//			
				for (int level = 0; level < projectedRowDefs.length; level++) {
					final RowDef rowDef = projectedRowDefs[level];
					if (rowDef.getHKeyDepth() > depth) {
						break;
					}
					hKey.indexTo(rowDef.getHKeyDepth());
					if (differsAt < hKey.getIndex()) {
						//
						// This is an ancestor row different from what was
						// previously delivered so we need to prepare it.
						//
						final int keySize = hKey.getEncodedSize();
						hKey.setEncodedSize(hKey.getIndex());
						hEx.fetch();
						prepareRow(hEx, level, rowDef.getRowDefId(), rowDef
								.getColumnOffset());
						hKey.setEncodedSize(keySize);

						if (level < pendingFromLevel) {
							pendingFromLevel = level;
						}
						if (level >= pendingToLevel) {
							pendingToLevel = level + 1;
						}
					}
				}
				hKey.copyTo(lastKey);
				traverseMode = true;
				//
				// Repeat main while-loop to flush any available pending rows
				//
				continue;
			}

			if (traverseMode) {
				//
				// Traverse
				//
				final boolean found = hEx.traverse(direction, hFilter,
						Integer.MAX_VALUE);
				direction = ascending ? Key.GT : Key.LT;
				if (!found
						|| hKey.firstUniqueByteIndex(lastKey) < indexPinnedKeySize) {
					if (indexDef == null) {
						more = false;
						return false;
					}
					traverseMode = false;
					//
					// To outer while-loop
					//
					continue;
				}
				final int depth = hKey.getDepth();
				for (int level = projectedRowDefs.length; --level >= 0;) {
					if (depth == projectedRowDefs[level].getHKeyDepth()) {
						prepareRow(hEx, level, projectedRowDefs[level]
								.getRowDefId(), projectedRowDefs[level]
								.getColumnOffset());
						hKey.copyTo(lastKey);
						if (level < pendingFromLevel) {
							pendingFromLevel = level;
						}
						pendingToLevel = level + 1;
						break;
					}
				}
			}
		}
	}

	void prepareRow(final Exchange exchange, final int level,
			final int expectedRowDefId, final int columnOffset)
			throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("Preparing row at " + exchange);
		}
		final byte[] bytes = exchange.getValue().getEncodedBytes();
		final int size = exchange.getValue().getEncodedSize();
		int rowDataSize = size + RowData.ENVELOPE_SIZE;

		if (rowDataSize < RowData.MINIMUM_RECORD_LENGTH) {
			if (LOG.isErrorEnabled()) {
				LOG.error("Value at " + exchange.getKey()
						+ " is not a valid row - skipping");
			}
			throw new StoreException(HA_ERR_INTERNAL_ERROR,
					"Corrupt RowData at " + exchange.getKey());
		} else {
			final int rowDefId = CServerUtil.getInt(bytes, RowData.O_ROW_DEF_ID
					- RowData.LEFT_ENVELOPE_SIZE);
			if (rowDefId != expectedRowDefId) {
				//
				// Add code to here to evolve data to required expectedRowDefId
				//
				throw new StoreException(HA_ERR_INTERNAL_ERROR,
						"Unable to convert rowDefId " + rowDefId
								+ " to expected rowDefId " + expectedRowDefId);
			}

			final RowData rowData = pendingRowData[level];
			byte[] buffer = rowData.getBytes();
			if (rowDataSize > buffer.length) {
				buffer = new byte[rowDataSize + INITIAL_BUFFER_SIZE];
				rowData.reset(buffer);
			}
			//
			// Assemble the Row in a byte array to allow column
			// elision
			//
			CServerUtil.putInt(buffer, RowData.O_LENGTH_A, rowDataSize);
			CServerUtil.putChar(buffer, RowData.O_SIGNATURE_A,
					RowData.SIGNATURE_A);
			System
					.arraycopy(exchange.getValue().getEncodedBytes(), 0,
							buffer, RowData.O_FIELD_COUNT, exchange.getValue()
									.getEncodedSize());
			CServerUtil.putChar(buffer, RowData.O_SIGNATURE_B + rowDataSize,
					RowData.SIGNATURE_B);
			CServerUtil.putInt(buffer, RowData.O_LENGTH_B + rowDataSize,
					rowDataSize);
			rowData.prepareRow(0);

			// Remove unwanted columns
			//
			rowData.elide(columnBitMap, columnOffset - columnBitMapOffset,
					columnBitMapWidth);

		}
	}

	boolean deliverRow(final RowData rowData, final ByteBuffer payload)
			throws IOException {
		if (rowData.getRowSize() + 4 < payload.limit() - payload.position()) {
			payload.put(rowData.getBytes(), rowData.getRowStart(), rowData
					.getRowSize());
			if (store.isVerbose() && LOG.isInfoEnabled()) {
				LOG.debug("Select row: "
						+ rowData.toString(store.getRowDefCache()));
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean hasMore() {
		return more;
	}

	@Override
	public void close() {
		if (hEx != null) {
			store.releaseExchange(hEx);
			hEx = null;
		}
		if (iEx != null) {
			store.releaseExchange(iEx);
			iEx = null;
		}
	}
}