package com.mead.conditions.rdf;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.system.Txn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class RdfService {

    @Value("${mead.rdf.data-file}")
    private Resource rdfFile;

    // Dataset (instead of plain Model) lets us use safe read/write transactions.
    @Getter
    private final Dataset dataset = DatasetFactory.createTxnMem();

    @PostConstruct
    public void loadRdfOnStartup() {
        try (InputStream in = rdfFile.getInputStream()) {
            Txn.executeWrite(dataset, () -> {
                // Load into the default graph of this dataset.
                RDFDataMgr.read(dataset.getDefaultModel(), in, Lang.TURTLE);
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RDF file: " + rdfFile, e);
        }
    }

}
