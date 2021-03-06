/*
 *  Copyright (C) 2017 Daniel H. Huson
 *
 *  (Some files contain contributions from other authors, who are then mentioned separately.)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package megan.biom;

import jloda.graph.Edge;
import jloda.graph.Node;
import jloda.graph.NodeData;
import jloda.graph.NodeSet;
import jloda.util.Basic;
import jloda.util.CanceledException;
import jloda.util.ProgressListener;
import megan.core.Director;
import megan.viewer.MainViewer;
import megan.viewer.TaxonomicLevels;
import megan.viewer.TaxonomyData;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;

/**
 * export a taxonomic analysis in biom format
 * Daniel Huson, 7.2012
 */
public class BiomExportTaxonomy {
    /**
     * export taxon name to counts mapping
     *
     * @param dir
     * @param file
     * @param progressListener
     * @return lines written
     */
    public static int apply(Director dir, File file, boolean officialRanksOnly, ProgressListener progressListener) throws IOException, CanceledException {
        final BiomData biomData = new BiomData(file.getPath());

        biomData.setType(BiomData.AcceptableTypes.Taxon_table.toString());
        biomData.setMatrix_type(BiomData.AcceptableMatrixTypes.dense.toString());
        biomData.setMatrix_element_type(BiomData.AcceptableMatrixElementTypes.Int.toString());
        biomData.setComment("Taxonomy classification computed by MEGAN");

        final MainViewer viewer = dir.getMainViewer();

        final java.util.List<String> names = dir.getDocument().getSampleNames();
        int numberOfCols = names.size();
        final LinkedList<Map> colList = new LinkedList<>();
        for (String name : names) {
            final Map<String, Object> colItem = new StringMap<>();
            colItem.put("id", Basic.getFileNameWithoutPath(Basic.getFileBaseName(name)));
            colItem.put("metadata", new StringMap<>());
            colList.add(colItem);
        }

        biomData.setColumns(colList.toArray(new Map[colList.size()]));

        final NodeSet selectedNodes = viewer.getSelectedNodes();
        if (selectedNodes.size() == 0) {
            throw new IOException("No nodes selected");
        }

        progressListener.setSubtask("Processing taxa");
        progressListener.setMaximum(selectedNodes.size());
        progressListener.setProgress(0);
        final LinkedList<Map> rowList = new LinkedList<>();
        final LinkedList<int[]> dataList = new LinkedList<>();

        visitSelectedLeavesRec(viewer, viewer.getTree().getRoot(), selectedNodes, new Vector<String>(), rowList, dataList, officialRanksOnly, progressListener);

        int numberOfRows = rowList.size();
        biomData.setRows(rowList.toArray(new Map[numberOfRows]));

        biomData.setShape(new int[]{numberOfRows, numberOfCols});

        final int[][] data = new int[numberOfRows][];
        int j = 0;
        for (int[] dataRow : dataList) {
            data[j++] = dataRow;
        }
        biomData.setData(data);

        System.err.println("Writing file: " + file);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            biomData.write(w);
        }
        return numberOfRows;
    }

    /**
     * recursively visit all the selected leaves
     *  @param viewer
     * @param v
     * @param selected
     * @param path
     * @param rowList
     * @param dataList
     */
    private static void visitSelectedLeavesRec(MainViewer viewer, Node v, NodeSet selected, Vector<String> path,
                                               LinkedList<Map> rowList, LinkedList<int[]> dataList, boolean officialRanksOnly, ProgressListener progressListener) throws CanceledException {

        if (v.getOutDegree() > 0 || selected.contains(v)) {
            final Integer taxId = (Integer) v.getInfo();
            String taxName = v == viewer.getTree().getRoot() ? "Root" : TaxonomyData.getName2IdMap().get(taxId);
            {
                int a = taxName.indexOf("<");
                int b = taxName.lastIndexOf(">");
                if (0 < a && a < b && b == taxName.length() - 1)
                    taxName = taxName.substring(0, a).trim(); // remove trailing anything in < > brackets
            }
            final int rank = TaxonomyData.getTaxonomicRank(taxId);
            boolean addedPathElement = false;

            if (!officialRanksOnly || TaxonomicLevels.isMajorRank(rank)) {
                if (officialRanksOnly) {
                    char letter = Character.toLowerCase(TaxonomicLevels.getName(rank).charAt(0));
                    path.addElement(String.format("%c__%s", letter, taxName));
                } else
                    path.addElement(taxName);
                addedPathElement = true;

                if (selected.contains(v)) {
                    NodeData nodeData = viewer.getNodeData(v);
                    if (nodeData != null) {
                        int[] values;
                        if (v.getOutDegree() == 0)
                            values = nodeData.getSummarized();
                        else
                            values = nodeData.getAssigned();
                        final Map<String, Object> rowItem = new StringMap<>();
                        rowItem.put("id", "" + taxId);
                        final Map<String, Object> metadata = new StringMap<>();
                        final ArrayList<String> classification = new ArrayList<>(path.size());
                        classification.addAll(path);
                        metadata.put("taxonomy", classification);
                        rowItem.put("metadata", metadata);
                        rowList.add(rowItem);
                        dataList.add(values);
                    }
                }
            }
            progressListener.incrementProgress();

            for (Edge e = v.getFirstOutEdge(); e != null; e = v.getNextOutEdge(e)) {
                visitSelectedLeavesRec(viewer, e.getTarget(), selected, path, rowList, dataList, officialRanksOnly, progressListener);
            }
            if (addedPathElement)
                path.setSize(path.size() - 1);
        }
    }

}
