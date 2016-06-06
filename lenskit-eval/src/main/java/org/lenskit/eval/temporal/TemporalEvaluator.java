/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.eval.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.grouplens.lenskit.util.io.CompressionMode;
import org.lenskit.LenskitConfiguration;
import org.lenskit.LenskitRecommenderEngine;
import org.lenskit.ModelDisposition;
import org.lenskit.api.ItemRecommender;
import org.lenskit.api.Recommender;
import org.lenskit.api.RecommenderBuildException;
import org.lenskit.api.Result;
import org.lenskit.data.dao.SortOrder;
import org.lenskit.data.events.Event;
import org.lenskit.data.history.UserHistory;
import org.lenskit.data.packed.BinaryRatingDAO;
import org.lenskit.data.ratings.Rating;
import org.lenskit.eval.traintest.AlgorithmInstance;
import org.lenskit.specs.eval.AlgorithmSpec;
import org.lenskit.specs.eval.SimulateSpec;
import org.lenskit.util.collections.LongUtils;
import org.lenskit.util.io.ObjectStreams;
import org.lenskit.util.table.TableLayout;
import org.lenskit.util.table.TableLayoutBuilder;
import org.lenskit.util.table.writer.CSVWriter;
import org.lenskit.util.table.writer.TableWriter;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.sqrt;

public class TemporalEvaluator {
    private final SimulateSpec spec;
    private Random rng;
    private AlgorithmInstance algorithm;
    private BinaryRatingDAO dataSource;

    public TemporalEvaluator(SimulateSpec spec) {
        this.spec = spec;
        rng = new Random();
    }

    public TemporalEvaluator() {
        spec = new SimulateSpec();
        spec.setRebuildPeriod(24 * 3600);
        spec.setListSize(10);
    }

    /**
     * Adds an algorithmInfo
     *
     * @param algo The algorithmInfo added
     * @return Itself to allow  chaining
     */
    public TemporalEvaluator setAlgorithm(AlgorithmInstance algo) {
        algorithm = algo;
        return this;
    }

    /**
     * An algorithm instance constructed with a name and Lenskit configuration
     *
     * @param name   Name of algorithm instance
     * @param config Lenskit configuration
     */
    public TemporalEvaluator setAlgorithm(String name, LenskitConfiguration config) {
        algorithm = new AlgorithmInstance(name, config);
        return this;
    }


    /**
     * @param dao The datasource to be added to the command.
     * @return Itself to allow for  method chaining.
     */
    public TemporalEvaluator setDataSource(BinaryRatingDAO dao) {
        dataSource = dao;
        return this;
    }

    /**
     * @param file The file contains the ratings input data
     * @return itself
     */
    public TemporalEvaluator setDataSource(File file) throws IOException {
        dataSource = BinaryRatingDAO.open(file);
        return this;
    }

    /**
     * @param file The file set as the output of the command
     * @return Itself for  method chaining
     */
    public TemporalEvaluator setPredictOutputFile(File file) {
        spec.setOutputFile(file.toPath());
        return this;
    }

    /**
     * Get the output file for extended output (lines of JSON).
     * @return the output file.
     */
    @Nullable
    public Path getExtendedOutputFile() {
        return spec.getExtendedOutputFile();
    }

    /**
     * Set the output file for extended output (lines of JSON).
     * @param file The output file name.
     * @return The evaluator (for chaining).
     */
    public TemporalEvaluator setExtendedOutputFile(@Nullable File file) {
        return setExtendedOutputFile(file != null ? file.toPath() : null);
    }

    /**
     * Set the output file for extended output (lines of JSON).
     * @param file The output file name.
     * @return The evaluator (for chaining).
     */
    public TemporalEvaluator setExtendedOutputFile(@Nullable Path file) {
        spec.setExtendedOutputFile(file);
        return this;
    }

    /**
     * @param time The time to rebuild
     * @param unit Unit of time set
     * @return Itself for  method chaining
     */
    public TemporalEvaluator setRebuildPeriod(Long time, TimeUnit unit) {
        spec.setRebuildPeriod(unit.toSeconds(time));
        return this;
    }

    /**
     * @param seconds default rebuild period in seconds
     * @return Itself for  method chaining
     */
    public TemporalEvaluator setRebuildPeriod(long seconds) {
        spec.setRebuildPeriod(seconds);
        return this;
    }

    /**
     * sets the size of recommendationss list size
     *
     * @param lSize size of list to be set
     * @return returns itself
     */
    public TemporalEvaluator setListSize(int lSize) {
        spec.setListSize(lSize);
        return this;
    }

    /**
     * @return Returns prediction output file
     */
    public Path getPredictOutputFile() {
        return spec.getOutputFile();
    }

    /**
     * @return Returns rebuild period
     */
    public long getRebuildPeriod() {
        return spec.getRebuildPeriod();
    }

    /**
     * @return size of recommendation list
     */
    public int getListSize() {
        return spec.getListSize();
    }

    private void loadInputs() throws IOException {
        Path dataFile = spec.getInputFile();
        Preconditions.checkState(dataSource != null && dataFile != null, "no data file specified");
        AlgorithmSpec algoSpec = spec.getAlgorithm();
        Preconditions.checkState(algorithm != null && algoSpec != null,
                                 "no algorithm specified");

        if (dataSource == null) {
            dataSource = BinaryRatingDAO.open(dataFile.toFile());
        }
        if (algorithm == null) {
            // FIXME Support multiple algorithms
            algorithm = AlgorithmInstance.fromSpec(spec.getAlgorithm(), null).get(0);
        }
    }

    /**
     * During the evaluation, it will replay the ratings, try to predict each one, and
     * write the prediction, TARMSE and the rating to the output file
     */
    public void execute() throws IOException, RecommenderBuildException {
        loadInputs();

        //Initialize recommender engine and recommender
        LenskitRecommenderEngine lre = null;
        Recommender recommender = null;

        //Start try block -- will try to write output on file
        try (TableWriter tableWriter = openOutput();
             SequenceWriter extWriter = openExtendedOutput()) {

            List<Rating> ratings = ObjectStreams.makeList(dataSource.streamEvents(Rating.class, SortOrder.TIMESTAMP));
            BinaryRatingDAO limitedDao = dataSource.createWindowedView(0);

            //Initialize local variables, will use to calculate RMSE
            double sse = 0;
            int n = 0;
            // Initialize build parameters
            long buildTime = 0L;
            int buildsCount = 0;

            //Loop through ratings
            for (Rating r : ratings) {
                Map<String,Object> json = new HashMap<>();
                json.put("userId", r.getUserId());
                json.put("itemId", r.getItemId());
                json.put("timestamp", r.getTimestamp());
                json.put("rating", r.getValue());

                if (r.getTimestamp() > 0 && limitedDao.getLimitTimestamp() < r.getTimestamp()) {
                    limitedDao = dataSource.createWindowedView(r.getTimestamp());
                    LenskitConfiguration config = new LenskitConfiguration();
                    config.addComponent(limitedDao);

                    //rebuild recommender system if its older then rebuild period set or null
                    if ((r.getTimestamp() - buildTime >= spec.getRebuildPeriod()) || lre == null) {
                        buildTime = r.getTimestamp();
                        lre = LenskitRecommenderEngine.newBuilder()
                                                      .addConfiguration(algorithm.getConfigurations().get(0))
                                                      .addConfiguration(config, ModelDisposition.EXCLUDED)
                                                      .build();
                        buildsCount++;
                    }
                    if (recommender != null) {
                        recommender.close();
                    }
                    recommender = lre.createRecommender(config);
                }

                 //get prediction score
                double predict = Double.NaN;
                Result predictionResult = recommender.getRatingPredictor().predict(r.getUserId(), r.getItemId());

                //check result to avoid null exception
                if (predictionResult != null) {
                    predict = predictionResult.getScore();
                    json.put("prediction", predict);
                } else {
                    json.put("prediction", null);
                }

                /***calculate Time Averaged RMSE***/
                double rmse = 0.0;
                if (!Double.isNaN(predict)) {
                    double err = predict - r.getValue();
                    sse += err * err;
                    n++;
                    rmse = sqrt(sse / n);
                }

                // Compute recommendations
                Integer rank = null;
                ItemRecommender irec = recommender.getItemRecommender();
                if (irec != null) {
                    rank = getRecommendationRank(limitedDao, r, json, irec);

                }

                /**writes the Prediction Score, Rank and TARMSE on file.**/
                tableWriter.writeRow(r.getUserId(), r.getItemId(), r.getValue(), r.getTimestamp(),
                                     predict, rmse, r.getTimestamp() - buildTime, rank, buildsCount);
                if (extWriter != null) {
                    extWriter.write(json);
                }
            } // loop ratings

        } finally {
            if (recommender != null) {
                recommender.close();
            }
        }
    }

    /**
     * Get the rank of the recommended item.
     * @param dao The limited DAO.
     * @param rating The rating.
     * @param json The JSON object being built.
     * @param irec The item recommender.
     * @return The rank, or `null` if the item is not recommended.
     */
    @Nullable
    private Integer getRecommendationRank(BinaryRatingDAO dao, Rating rating, Map<String, Object> json, ItemRecommender irec) {
        Integer rank; /***calculate recommendation rank***/
                    /* set of candidates that includes current item +
                       listsize-1 random values from (items from dao - items rated by user) */
        LongSet candidates = new LongOpenHashSet();
                    /* Users *not* to include in candidate set */
        LongSet excludes = new LongOpenHashSet();
        // include the target item...
        candidates.add(rating.getItemId());
        // .. and exlude it from being added again
        excludes.add(rating.getItemId());

        //Check if events for users exists to avoid NULL exception
        UserHistory<Event> profile = dao.getEventsForUser(rating.getUserId());
        if (profile != null) {
            excludes.addAll(profile.itemSet());
        }

        // Add a random set of decoy items
        candidates.addAll(LongUtils.randomSubset(dao.getItemIds(), spec.getListSize() - 1, excludes, rng));

        // get list of recommendations
        List<Long> recs = irec.recommend(rating.getUserId(), spec.getListSize(), candidates, null);
        json.put("recommendations", recs);
        rank = recs.indexOf(rating.getItemId());
        if (rank >= 0) {
            //increment index to get correct rank
            rank++;
        } else {
            rank = null;
        }
        return rank;
    }

    @Nullable
    private TableWriter openOutput() throws IOException {
        Path path = spec.getOutputFile();
        if (path == null) {
            return null;
        }

        TableLayoutBuilder tlb = new TableLayoutBuilder();

        tlb.addColumn("User")
           .addColumn("Item")
           .addColumn("Rating")
           .addColumn("Timestamp")
           .addColumn("Prediction")
           .addColumn("TARMSE")
           .addColumn("ModelAge")
           .addColumn("Rank")
           .addColumn("Rebuilds");

        TableLayout layout = tlb.build();

        return CSVWriter.open(path.toFile(), layout, CompressionMode.AUTO);
    }

    @Nullable
    private SequenceWriter openExtendedOutput() throws IOException {
        Path path = spec.getExtendedOutputFile();
        if (path == null) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectWriter w = mapper.writer();
        return w.writeValues(path.toFile());
    }
}

