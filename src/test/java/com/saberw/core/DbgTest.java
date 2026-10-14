package com.saberw.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;

public class DbgTest {
    @Test
    void dbg() throws Exception {
        CompressionSettings s = new CompressionSettings(3, 2, 3, 0, false);
        Compressor comp = new Compressor(s);
        Method m = Compressor.class.getDeclaredMethod("findPhrases", List.class);
        m.setAccessible(true);
        String text = "это длинная фраза которая повторяется в тексте. "
                + "это длинная фраза которая повторяется в тексте. финал";
        var tokens = SaberTokenizer.tokenize(Shielder.shield(text));
        @SuppressWarnings("unchecked")
        List<Compressor.Candidate> cands = (List<Compressor.Candidate>) m.invoke(comp, tokens);
        for (Compressor.Candidate c : cands) {
            if (c.tokenCount() >= 5) System.out.println("DBG|F=" + c.frequency() + "|L=" + c.charLength()
                    + "|T=" + c.tokenCount() + "|" + c.raw());
        }
        // now run the full compress and dump dictionary phrase entries
        Compressor.Result r = comp.compress(text);
        System.out.println("DBGOUT|" + r.compressedText().replace('\n',' ') + "|");
        r.dictionary().entries().forEach(e -> {
            String v = e.value().tokens().get(0).text();
            if (v.indexOf(' ') >= 0) System.out.println("DBGP|code=" + e.code() + "|freq=" + e.frequency() + "|applied=" + e.codeAppliedCount() + "|" + v);
        });
    }
}
