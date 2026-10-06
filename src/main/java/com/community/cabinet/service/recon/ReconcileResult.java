package com.community.cabinet.service.recon;

import java.util.List;

public record ReconcileResult(
        List<ReconciledLine> lines,
        List<Verdict> verdicts,
        boolean uncertain,
        String summary
) {}
