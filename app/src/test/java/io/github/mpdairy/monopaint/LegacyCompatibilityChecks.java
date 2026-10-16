package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.Arrays;
import java.util.Base64;

/**
 * Backward compatibility with drawings saved before the reference image existed.
 *
 * <p>The fixtures below are <b>real files written by the pre-reference app</b> (commit
 * 8743286, before any reference code landed), not files this version wrote and re-read.
 * They cover every format that can be found on a tablet: TSM1 (a bare tone plane),
 * TSM3 (layers with opacity, including a hidden layer), TSM4 (masking fluid), the
 * multi-page ZIP book, and a crash recovery file. If a change here makes any fixture
 * fail to open, real artists' drawings are broken.</p>
 *
 * <p>Regenerate with the pre-reference tree if these ever need replacing; never replace
 * one with a file this version wrote, or the test stops proving anything.</p>
 */
final class LegacyCompatibilityChecks {
    /** The oldest form: one tone plane, no layers, no opacity. */
    private static final String TSM1 =
            "VFNNMQAAAAwAAAAJAAAAALD4b6V4nGMwSpl2goE+BADRiCmb";
    /** Three layers with opacity, the third hidden. */
    private static final String TSM3_LAYERS =
            "VFNNMwAAABAAAAAQeJxjYGBgZmBgYGJg90msTC1SMGRM+U86ECFDz6AFDKQDcvQMXgBNCkaM2jQJ3zs0"
            + "MZUmgCbBO5QSCzQpGDOQUypQA0QNkL0YYIAiYFAlFqFrTuoAbRYHxQ==";
    /** Masking fluid over paint: the newest pre-reference page format. */
    private static final String TSM4_MASK =
            "VFNNNAAAABAAAAAQeJxjYGBgZAABdp/EytQiBUPGlP9UAHLUMGSAAAMVAFUMGSBAlTCkgoqBAlQJQyqo"
            + "GEgQbrttKwDItQL3";
    /** A three-page book mixing page formats, in the ZIP container. */
    private static final String BOOK =
            "UEsDBAoAAAgAAB03UF06vNO/FwAAABcAAAAIAAAAbWFuaWZlc3RUU0IyAAAAFAAAABQAAAADAAAAAAAA"
            + "AFBLAwQKAAAIAAAdN1BdjQc9NlYAAABWAAAACwAAAHBhZ2VzLzEudHNtVFNNMwAAABQAAAAUeJxjYGBg"
            + "YmBgYGRg90msTC1SMGRM+U804Cde6SggGzAQDUhQOgrIB9CsYkRKVhmE4MRAO4D6YKBTBkVgaLseJ7D8"
            + "aBcDAFYoJSFQSwMECgAACAAAHTdQXdt0AVw5AAAAOQAAAAsAAABwYWdlcy8yLnRzbVRTTTMAAAAUAAAA"
            + "FHicY2BgYGQAAXafxMrUIgVDxpT/tAMVNDR72AIG2gFamj2sQWFE9kIAv/WTiFBLAwQKAAAIAAAdN1Bd"
            + "XOmPXUQAAABEAAAACwAAAHBhZ2VzLzMudHNtVFNNNAAAABQAAAAUeJxjYGBgZAABdp/EytQiBUPGlP+j"
            + "YFABhlEwqACl0UmVRDEK4IDS6KRKohgFKKA6avFUAKwPIdZQSwECCgAKAAAIAAAdN1BdOrzTvxcAAAAX"
            + "AAAACAAAAAAAAAAAAAAAAAAAAAAAbWFuaWZlc3RQSwECCgAKAAAIAAAdN1BdjQc9NlYAAABWAAAACwAA"
            + "AAAAAAAAAAAAAAA9AAAAcGFnZXMvMS50c21QSwECCgAKAAAIAAAdN1Bd23QBXDkAAAA5AAAACwAAAAAA"
            + "AAAAAAAAAAC8AAAAcGFnZXMvMi50c21QSwECCgAKAAAIAAAdN1BdXOmPXUQAAABEAAAACwAAAAAAAAAA"
            + "AAAAAAAeAQAAcGFnZXMvMy50c21QSwUGAAAAAAQABADhAAAAiwEAAAAA";
    /** A crash recovery file pointing at a named drawing. */
    private static final String RECOVERY =
            "VFNSMgAHT2xkIENhdAFQSwMECgAACAAAHTdQXTq8078XAAAAFwAAAAgAAABtYW5pZmVzdFRTQjIAAAAU"
            + "AAAAFAAAAAMAAAAAAAAAUEsDBAoAAAgAAB03UF2NBz02VgAAAFYAAAALAAAAcGFnZXMvMS50c21UU00z"
            + "AAAAFAAAABR4nGNgYGBiYGBgZGD3SaxMLVIwZEz5TzTgJ17pKCAbMBANSFA6CsgH0KxiREpWGYTgxEA7"
            + "gPpgoFMGRWBoux4nsPxoFwMAViglIVBLAwQKAAAIAAAdN1Bd23QBXDkAAAA5AAAACwAAAHBhZ2VzLzIu"
            + "dHNtVFNNMwAAABQAAAAUeJxjYGBgZAABdp/EytQiBUPGlP+0AxU0NHvYAgbaAVqaPaxBYUT2QgC/9ZOI"
            + "UEsDBAoAAAgAAB03UF1c6Y9dRAAAAEQAAAALAAAAcGFnZXMvMy50c21UU000AAAAFAAAABR4nGNgYGBk"
            + "AAF2n8TK1CIFQ8aU/6NgUAGGUTCoAKXRSZVEMQrggNLopEqiGAUooDpq8VQArA8h1lBLAQIKAAoAAAgA"
            + "AB03UF06vNO/FwAAABcAAAAIAAAAAAAAAAAAAAAAAAAAAABtYW5pZmVzdFBLAQIKAAoAAAgAAB03UF2N"
            + "Bz02VgAAAFYAAAALAAAAAAAAAAAAAAAAAD0AAABwYWdlcy8xLnRzbVBLAQIKAAoAAAgAAB03UF3bdAFc"
            + "OQAAADkAAAALAAAAAAAAAAAAAAAAALwAAABwYWdlcy8yLnRzbVBLAQIKAAoAAAgAAB03UF1c6Y9dRAAA"
            + "AEQAAAALAAAAAAAAAAAAAAAAAB4BAABwYWdlcy8zLnRzbVBLBQYAAAAABAAEAOEAAACLAQAAAAA=";

    public static void main(String[] args) throws Exception {
        opensWithoutAReference(); reSavesUnchanged(); upgradesToReference(); booksAndRecovery();
        System.out.println("PASS: pre-reference TSM1/TSM3/TSM4 pages, multi-page books and recovery files open, "
                + "re-save unchanged, and take a reference without disturbing the paint");
    }

    private static byte[] fixture(String base64) { return Base64.getDecoder().decode(base64); }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Every old page opens, keeps its own content, and reports no reference. */
    private static void opensWithoutAReference() throws Exception {
        ToneDocument bare = DocumentCodec.read(new ByteArrayInputStream(fixture(TSM1)));
        check(bare.width == 12 && bare.height == 9, "TSM1 keeps its size");
        check(bare.layerCount() == 1 && !bare.hasReference() && !bare.hasMask(), "TSM1 opens as one plain layer");
        check(bare.compositeTone(3, 3) == 200, "TSM1 keeps its tones");

        ToneDocument layers = DocumentCodec.read(new ByteArrayInputStream(fixture(TSM3_LAYERS)));
        check(layers.layerCount() == 3 && !layers.hasReference(), "TSM3 keeps its three layers, no reference");
        check(layers.layerOpacity(1) == 43, "TSM3 keeps per-layer opacity");
        check(!layers.layerVisible(2), "TSM3 keeps the hidden layer hidden");
        check(layers.compositeTone(3, 3) == 20, "TSM3 composites the bottom layer through");

        ToneDocument masked = DocumentCodec.read(new ByteArrayInputStream(fixture(TSM4_MASK)));
        check(masked.hasMask() && !masked.hasReference(), "TSM4 keeps masking fluid, no reference");
        System.out.println("PASS: pre-reference pages open with their own layers, opacity, hidden state and fluid");
    }

    /** Re-saving an old page must not lose or invent anything. */
    private static void reSavesUnchanged() throws Exception {
        for (String fixture : new String[]{TSM1, TSM3_LAYERS, TSM4_MASK}) {
            ToneDocument original = DocumentCodec.read(new ByteArrayInputStream(fixture(fixture)));
            ToneDocument again = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(original)));
            check(again.layerCount() == original.layerCount(), "re-save keeps the layer count");
            check(again.hasReference() == original.hasReference(), "re-save neither adds nor drops a reference");
            check(again.hasMask() == original.hasMask(), "re-save keeps the masking fluid");
            for (int i = 0; i < original.layerCount(); i++) {
                check(Arrays.equals(original.layerSnapshot().layers.get(i).tones,
                        again.layerSnapshot().layers.get(i).tones), "re-save keeps layer " + i + " pixels");
                check(again.layerOpacity(i) == original.layerOpacity(i), "re-save keeps layer " + i + " opacity");
                check(again.layerVisible(i) == original.layerVisible(i), "re-save keeps layer " + i + " visibility");
            }
        }
        System.out.println("PASS: re-saving a pre-reference page preserves every layer, opacity and flag exactly");
    }

    /** An old drawing taking a reference must keep its paint above it, at any layer count. */
    private static void upgradesToReference() throws Exception {
        byte[] lum = new byte[64], alpha = new byte[64];
        for (int i = 0; i < 64; i++) { lum[i] = (byte) ((i % 2) * 255); alpha[i] = (byte) 255; }
        float[] placed = ReferenceImage.place(8, 8, 8, 8, 1, 0);

        ToneDocument doc = DocumentCodec.read(new ByteArrayInputStream(fixture(TSM3_LAYERS)));
        int paintBefore = doc.compositeTone(3, 3);
        doc.setReference(new ReferenceImage(lum, alpha, 8, 8, placed));
        check(doc.isReference(0) && doc.layerCount() == 4, "the reference is inserted below the old layers");
        check(doc.activeLayer() == 3, "the artist is pushed off the reference onto their own layer");
        check(doc.compositeTone(3, 3) == paintBefore, "the old paint still covers the new reference");
        ToneDocument saved = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(doc)));
        check(saved.hasReference() && saved.layerCount() == 4, "the upgraded page round trips");
        check(Arrays.equals(doc.layerSnapshot().layers.get(2).tones, saved.layerSnapshot().layers.get(2).tones),
                "the old paint survives the upgrade byte for byte");

        // The tight case: an old file already at the 8-layer limit takes a reference to fill MAX_STACK.
        ToneDocument eight = new ToneDocument(8, 8);
        while (eight.layerCount() < ToneDocument.MAX_LAYERS) eight.addLayer();
        eight.begin(); eight.paintTone(1, 1, 40); eight.finish();
        ToneDocument old = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(eight)));
        check(old.layerCount() == ToneDocument.MAX_LAYERS && !old.hasReference(), "old file sits at the 8-layer limit");
        old.setReference(new ReferenceImage(lum, alpha, 8, 8, ReferenceImage.place(4, 4, 8, 8, 1, 0)));
        check(old.layerCount() == ToneDocument.MAX_STACK, "reference + 8 paint layers fills the stack exactly");
        ToneDocument back = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(old)));
        check(back.paintLayers() == ToneDocument.MAX_LAYERS && back.hasReference(),
                "the full stack round trips as reference plus eight paint layers");
        try { back.addLayer(); throw new AssertionError("let a tenth layer in"); }
        catch (IllegalStateException expected) { /* the limit still holds */ }
        System.out.println("PASS: pre-reference drawings take a reference without disturbing paint, even at the layer limit");
    }

    /** The ZIP book and the crash recovery file, including the validate-only path for idle pages. */
    private static void booksAndRecovery() throws Exception {
        byte[] bytes = fixture(BOOK);
        DrawingBook book = BookCodec.read(new ByteArrayInputStream(bytes));
        check(book.count() == 3, "the old three-page book opens");
        book.select(0);
        check(book.current().layerCount() == 2 && !book.current().hasReference(), "book page one keeps its layers");
        book.select(1);
        check(book.current().layerCount() == 1 && !book.current().hasMask(), "book page two opens");
        book.select(2);
        check(book.current().hasMask() && !book.current().hasReference(), "book page three keeps its masking fluid");
        // Previews read page one alone; idle pages go through the validate-only path, which
        // must consume each format's bytes exactly or the whole book is rejected.
        check(BookCodec.readFirstPage(new ByteArrayInputStream(bytes)).layerCount() == 2,
                "the old book still previews its first page");
        // And a reference on one page of an old book must not disturb the others.
        byte[] lum = new byte[64], alpha = new byte[64];
        Arrays.fill(alpha, (byte) 255);
        book.select(0);
        book.current().setReference(new ReferenceImage(lum, alpha, 8, 8, ReferenceImage.place(4, 4, 8, 8, 1, 0)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BookCodec.write(out, book.snapshot());
        DrawingBook reopened = BookCodec.read(new ByteArrayInputStream(out.toByteArray()));
        reopened.select(0);
        check(reopened.current().hasReference(), "the reference page survives the book file");
        reopened.select(2);
        check(!reopened.current().hasReference() && reopened.current().hasMask(),
                "the other pages are untouched by the reference");
        System.out.println("PASS: old multi-page books open, preview, validate idle pages, and keep pages independent");

        RecoveryCodec.Recovered recovered = RecoveryCodec.read(new ByteArrayInputStream(fixture(RECOVERY)));
        check(recovered.path.equals("Old Cat"), "the old recovery keeps its drawing name");
        check(recovered.book.unsaved(), "an old recovery still counts as unsaved");
        check(recovered.book.count() == 3, "the old recovery keeps all its pages");
        recovered.book.select(0);
        check(!recovered.book.current().hasReference() && recovered.book.current().layerCount() == 2,
                "the recovered page keeps its layers and no reference");
        System.out.println("PASS: old crash recovery files restore with their name, pages and layers intact");
    }
}
