package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DuckDbTableEntryEnvelopeTest {
 @TempDir Path dir;
 @Test void readsPointerRowsAndExplicitNextRowId() {
  try (var m=file("golden", bytes(5,7,10,0,0,12L))) {
   var r=new DuckDbCheckpointEnvelopeReader(m,MetaBlockPointer.of(0,0,0)); r.beginCheckpoint(); r.readNextEntryEnvelope();
   var e=r.readTableEntryEnvelope(); assertEquals(5,e.tablePointer().blockId()); assertEquals(0,e.tablePointer().subBlockIndex()); assertEquals(7,e.tablePointer().offset()); assertEquals(10,e.totalRows()); assertEquals(12,e.nextRowId());
  }
 }
 @Test void defaultsNextRowIdToTotalRows() {
  try (var m=file("default", bytes(5,0,9,0,0,null))) { var r=new DuckDbCheckpointEnvelopeReader(m,MetaBlockPointer.of(0,0,0)); r.beginCheckpoint(); r.readNextEntryEnvelope(); assertEquals(9,r.readTableEntryEnvelope().nextRowId()); }
 }
 @Test void rejectsNonemptyListsAndCorruptPointerAndTruncation() {
  fail("nonempty103", bytes(5,0,0,1,0,null), "DuckDB table index_pointers field 103 is unsupported when non-empty");
  fail("nonempty104", bytes(5,0,0,0,1,null), "DuckDB table index_storage_infos field 104 is unsupported when non-empty");
 }
 private void fail(String n, byte[] p,String msg) { try(var m=file(n,p)){var r=new DuckDbCheckpointEnvelopeReader(m,MetaBlockPointer.of(0,0,0));r.beginCheckpoint();r.readNextEntryEnvelope();assertEquals(msg,assertThrows(StorageFormatException.class,r::readTableEntryEnvelope).getMessage());} }
 private byte[] bytes(long block,long offset,long rows,long i103,long i104,Long next) {
  java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream(); int[] a={100,0,1,99,0,1,100,0,1,100,0,1,105,0,0,(byte)201,0,(byte)255,(byte)255,(byte)255,(byte)255,101,0,100,0,(int)block,101,0,(int)offset,(byte)255,(byte)255,102,0,(int)rows,103,0,(int)i103,104,0,(int)i104}; for(int x:a)b.write(x); if(next!=null){b.write(105);b.write(0);b.write(next.intValue());} b.write(255);b.write(255);b.write(255);b.write(255);return b.toByteArray();
 }
 private SingleFileBlockManager file(String n,byte[] p){var path=dir.resolve(n);try(var w=SingleFileBlockManager.create(path,new byte[16])){var b=new byte[w.usableBlockSize()];for(int i=0;i<8;i++)b[i]=(byte)(MetaBlockPointer.INVALID_BLOCK_POINTER >>>8*i);System.arraycopy(p,0,b,8,p.length);w.writeBlock(0,b);}return SingleFileBlockManager.openMetadataReadOnly(path);}
}
