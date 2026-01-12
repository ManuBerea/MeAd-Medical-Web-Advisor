package com.mead.geography.rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShaclValidationTest {

    @Test
    void regionsRdf_conformsToPlaceShape() {
        Model data = loadModel("rdf/geography-data.ttl");
        Model shapes = loadModel("shacl/region-shapes.ttl");

        ValidationReport report = ShaclValidator.get()
                .validate(shapes.getGraph(), data.getGraph());

        assertThat(report.conforms())
                .withFailMessage("SHACL validation failed:\n%s", report.getEntries())
                .isTrue();
    }

    private Model loadModel(String classpathPath) {
        InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(classpathPath);
        assertThat(input)
                .withFailMessage("Missing classpath resource: %s", classpathPath)
                .isNotNull();

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, input, Lang.TURTLE);
        return model;
    }
}
