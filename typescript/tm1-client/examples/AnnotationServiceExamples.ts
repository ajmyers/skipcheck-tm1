import { AnnotationPatch, type Annotation, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for AnnotationService, quoted in its documentation.

export function annotate(tm1: Tm1Client): Promise<Annotation> {
  // @start region = annotate
  // A comment on one cell, as Planning Analytics Workspace makes it: the cell's elements by
  // dimension, in the cube's dimension order
  const cell = { Version: "Forecast", Region: "North", Measure: "Amount" };
  return tm1.annotations.create("Sales", cell, "Includes the March promotion");
  // @end
}

export async function read(tm1: Tm1Client): Promise<string[]> {
  // @start region = read
  // GET Annotations lists none on TM1 11.8, so read them by cube
  const comments: string[] = [];
  for await (const annotation of tm1.annotations.cubeAnnotations("Sales")) {
    comments.push(`${annotation.Creator}: ${annotation.Text}`);
  }
  return comments;
  // @end
}

export function edit(tm1: Tm1Client, id: string): Promise<void> {
  // @start region = edit
  // TM1 keeps Text and the commentValue PAW shows apart, so change both
  const text = "Includes the March and April promotions";
  return tm1.annotations.update(id, AnnotationPatch.builder().text(text).property("commentValue", text).build());
  // @end
}
