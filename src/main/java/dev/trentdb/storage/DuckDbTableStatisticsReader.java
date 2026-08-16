package dev.trentdb.storage;
import dev.trentdb.storage.format.MetaBlockPointer;import dev.trentdb.storage.format.StorageFormatException;import java.util.*;
/** Bounded V2 TableStatistics reader: BOOLEAN, INTEGER and BIGINT only. */
public final class DuckDbTableStatisticsReader {
 private final DuckDbBinaryMetadataReader r; private final List<DuckDbTableCreateInfo.ScalarLogicalType> types;
 public DuckDbTableStatisticsReader(SingleFileBlockManager m, MetaBlockPointer p,List<DuckDbTableCreateInfo.ScalarLogicalType> t){r=new DuckDbBinaryMetadataReader(new MetadataChainReader(m,p),m.activeHeader().storageCompatibility());types=List.copyOf(t);}
 public DuckDbTableStatistics read(){return readCursor().statistics();}
 Cursor readCursor(){r.beginObject();r.beginProperty(100);long n=r.beginList();if(n!=types.size())throw new StorageFormatException("DuckDB TableStatistics column count mismatch");var out=new ArrayList<DuckDbTableStatistics.Primitive>();for(var t:types)out.add(one(t));if(r.beginOptionalProperty(101))throw new StorageFormatException("DuckDB TableStatistics sample field 101 is unsupported");r.endObject();return new Cursor(new DuckDbTableStatistics(out),r);}
 record Cursor(DuckDbTableStatistics statistics,DuckDbBinaryMetadataReader reader){}
 private DuckDbTableStatistics.Primitive one(DuckDbTableCreateInfo.ScalarLogicalType t){var k=switch(t){case BOOLEAN->DuckDbTableStatistics.Kind.BOOLEAN;case INTEGER->DuckDbTableStatistics.Kind.INTEGER;case BIGINT->DuckDbTableStatistics.Kind.BIGINT;default->throw new StorageFormatException("DuckDB TableStatistics logical type is unsupported: "+t);};r.beginObject();r.beginProperty(100);boolean hn=r.readBoolean();r.beginProperty(101);boolean hnn=r.readBoolean();r.beginProperty(102);long d=r.readUnsignedLeb128();r.beginProperty(103);r.beginObject();OptionalLong min=bound(k,200);OptionalLong max=bound(k,201);r.endObject();r.endObject();return new DuckDbTableStatistics.Primitive(hn,hnn,d,k,min,max);}
 private OptionalLong bound(DuckDbTableStatistics.Kind k,int f){if(!r.beginOptionalProperty(f))return OptionalLong.empty();r.beginObject();r.beginProperty(100);boolean present=r.readBoolean();if(!present){r.endObject();return OptionalLong.empty();}r.beginProperty(101);long v=k==DuckDbTableStatistics.Kind.BOOLEAN?(r.readBoolean()?1:0):r.readSignedLeb128();r.endObject();return OptionalLong.of(v);}
}
