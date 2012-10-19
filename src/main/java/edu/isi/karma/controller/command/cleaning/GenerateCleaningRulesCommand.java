/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/
package edu.isi.karma.controller.command.cleaning;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONObject;

import au.com.bytecode.opencsv.CSVReader;
import edu.isi.karma.cleaning.MyLogger;
import edu.isi.karma.cleaning.Ruler;
import edu.isi.karma.cleaning.TNode;
import edu.isi.karma.cleaning.UtilTools;
import edu.isi.karma.controller.command.CommandException;
import edu.isi.karma.controller.command.WorksheetCommand;
import edu.isi.karma.controller.update.CleaningResultUpdate;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.rep.HNodePath;
import edu.isi.karma.rep.Node;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.rep.cleaning.RamblerTransformationExample;
import edu.isi.karma.rep.cleaning.RamblerTransformationInputs;
import edu.isi.karma.rep.cleaning.RamblerTransformationOutput;
import edu.isi.karma.rep.cleaning.RamblerValueCollection;
import edu.isi.karma.rep.cleaning.TransformationExample;
import edu.isi.karma.rep.cleaning.ValueCollection;
import edu.isi.karma.view.VWorkspace;
import edu.isi.karma.webserver.ServletContextParameterMap;
import edu.isi.karma.webserver.ServletContextParameterMap.ContextParameter;

public class GenerateCleaningRulesCommand extends WorksheetCommand {
	final String hNodeId;
	private Vector<TransformationExample> examples;
	RamblerTransformationInputs inputs;
	public String compResultString = ""; 

	public GenerateCleaningRulesCommand(String id, String worksheetId, String hNodeId, String examples) {
		super(id, worksheetId);
		this.hNodeId = hNodeId;
		this.examples = this.parseExample(examples);
		/************collect info************/
		MyLogger.logsth(worksheetId+" examples: "+examples);
		MyLogger.logsth("Time: "+System.currentTimeMillis());
		/*************************************/
		
	}
	public Vector<TransformationExample> parseExample(String example)
	{
		Vector<TransformationExample> x = new Vector<TransformationExample>();
		try
		{
			JSONArray jsa = new JSONArray(example);
			for(int i=0;i<jsa.length();i++)
			{
				String[] ary = new String[3];
				JSONObject jo = (JSONObject) jsa.get(i);
				String nodeid = (String)jo.get("nodeId");
				String before = (String)jo.getString("before");
				String after = (String)jo.getString("after");
				ary[0] = nodeid;
				ary[1] = "<_START>"+before+"<_END>";
				ary[2] = after;
				TransformationExample re = new RamblerTransformationExample(ary[1], ary[2], ary[0]);
				x.add(re);
			}
		}
		catch(Exception ex)
		{
			System.out.println(""+ex.toString());
		}
		return x;
	}
	private static Vector<String> getTopK(Set<String> res,int k,String cmpres)
	{
		String dirpathString = ServletContextParameterMap.getParameterValue(ContextParameter.USER_DIRECTORY_PATH);
		if(dirpathString.compareTo("")==0)
		{
			dirpathString = "./src/main/webapp/";
		}

		String trainPath = dirpathString+"grammar/features.arff";
		//
		String[] x = (String[])res.toArray(new String[res.size()]);
		System.out.println(""+x);
		//Vector<Double> scores = UtilTools.getScores(x, trainPath);
		Vector<Double> scores = UtilTools.getScores2(x,cmpres);
		System.out.println("Scores: "+scores);
		Vector<Integer> ins =UtilTools.topKindexs(scores, k);
		System.out.println("Indexs: "+ins);
		Vector<String> y = new Vector<String>();
		for(int i = 0; i<k&&i<ins.size();i++)
		{
			y.add(x[ins.get(i)]);
		}
		return y;
	}

	@Override
	public String getCommandName() {
		return GenerateCleaningRulesCommand.class.getSimpleName();
	}

	@Override
	public String getTitle() {
		return "Generate Cleaning Rules";
	}

	@Override
	public String getDescription() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CommandType getCommandType() {
		return CommandType.undoable;
	}
	@Override
	public UpdateContainer doIt(VWorkspace vWorkspace) throws CommandException {
		Worksheet wk = vWorkspace.getRepFactory().getWorksheet(worksheetId);
		// Get the HNode
		HashMap<String, String> rows = new HashMap<String,String>();
		HashMap<String, Integer> amb = new HashMap<String, Integer>();
		boolean firstCol = true;
		HNodePath selectedPath = null;
		List<HNodePath> columnPaths = wk.getHeaders().getAllPaths();
		for (HNodePath path : columnPaths) {
			if (path.getLeaf().getId().equals(hNodeId)) {
				selectedPath = path;
			}
		}
		Collection<Node> nodes = new ArrayList<Node>();
		wk.getDataTable().collectNodes(selectedPath, nodes);	
		for (Node node : nodes) {
			String id = node.getId();
			String originalVal = node.getValue().asString();
			rows.put(id, originalVal);
			this.compResultString += originalVal+"\n";
			calAmbScore(id,originalVal,amb);
		}
		RamblerValueCollection vc = new RamblerValueCollection(rows);
		inputs = new RamblerTransformationInputs(examples, vc);
		//generate the program
		boolean results = false;
		int iterNum = 0;
		RamblerTransformationOutput rtf = null;
		while(iterNum<5 && !results) // try to find any rule during 5 times running
		{
			rtf = new RamblerTransformationOutput(inputs);
			if(rtf.getTransformations().keySet().size()>0)
			{
				results = true;
			}
			iterNum ++;
		}
		Iterator<String> iter = rtf.getTransformations().keySet().iterator();
		Vector<ValueCollection> vvc = new Vector<ValueCollection>();
		HashMap<String, HashMap<String,Integer>> values = new HashMap<String, HashMap<String,Integer>>();
		HashMap<String,Vector<String>> js2tps = new HashMap<String,Vector<String>>();
		while(iter.hasNext())
		{
			String tpid = iter.next();
			ValueCollection rvco = rtf.getTransformedValues(tpid);
			if(rvco==null)
				continue;
			vvc.add(rvco);
			updateCandiScore(rvco,values);
			String reps = rvco.getJson().toString();
			if(js2tps.containsKey(reps))
			{
				js2tps.get(reps).add(tpid); // update the variance dic
			}
			else
			{
				Vector<String> tps = new Vector<String>();
				tps.add(tpid);
				js2tps.put(reps, tps);
			}
		}
		//get the best transformed result
		String bestRes = "";
		HashMap<String, Double> topkeys = new HashMap<String, Double>();
		String switcher = ServletContextParameterMap.getParameterValue(ContextParameter.MSFT);
		if( rtf.getTransformations().keySet().size()>0)
		{
			//ValueCollection rvco = rtf.getTransformedValues("BESTRULE");
			//bestRes = rvco.getJson().toString(); 
			//
			topkeys = getScore(amb, values,(switcher.compareTo("True")==0));
		}
		Vector<String> jsons = new Vector<String>();
		if(js2tps.keySet().size()!=0)
		{
			 bestRes = getTopK(js2tps.keySet(), 1,this.compResultString).get(0);
		}
		else
		{
			System.out.println("Didn't find any transformation programs");
		}
		
		//if true use msft algor, randomly choose the result, no top keys and no suggestions
		if(switcher.compareTo("True")==0)
		{
			bestRes = js2tps.keySet().iterator().next();
			jsons.add(bestRes);
			//topkeys.clear();
		}
		else 
		{
			jsons.addAll(js2tps.keySet());
		}
		return new UpdateContainer(new CleaningResultUpdate(hNodeId, jsons,js2tps,bestRes,topkeys.keySet()));
	}
	public void calAmbScore(String id,String org,HashMap<String, Integer> amb )
	{
		Ruler ruler = new Ruler();
		ruler.setNewInput(org);
		Vector<TNode> tNodes = ruler.vec;
		int tcnt = 1;
		for(int i=0;i<tNodes.size();i++)
		{
			if(tNodes.get(i).text.compareTo(" ")==0)
				continue;
			for(int j=0;j>i&&j<tNodes.size();j++)
			{
				if(tNodes.get(j).sameNode(tNodes.get(i)))
				{
					tcnt ++;
				}
			}
		}
		amb.put(id, tcnt);
	}
	public void updateCandiScore(ValueCollection rvco,HashMap<String, HashMap<String,Integer>> values)
	{
		Iterator<String> ids = rvco.getNodeIDs().iterator();
		while(ids.hasNext())
		{
			String id = ids.next();
			String value = rvco.getValue(id);
			HashMap<String, Integer> dict;
			if(values.containsKey(id))
			{
				dict = values.get(id);
			}
			else 
			{
				dict = new HashMap<String, Integer>();
				values.put(id, dict);
			}
			if(dict.containsKey(value))
			{
				dict.put(value, dict.get(value)+1);
			}
			else {
				dict.put(value, 1);
			}
		}
		return;
	}
	public HashMap<String, Double> getScore(HashMap<String,Integer> dicts,HashMap<String, HashMap<String,Integer>> values,boolean sw)
	{
		
		int topKsize = 1;
		if(sw)
			topKsize = Integer.MAX_VALUE;
		HashMap<String, Double> topK = new HashMap<String, Double>();
		Iterator<String> iditer = dicts.keySet().iterator();
		while (iditer.hasNext()) {
			String id = iditer.next();
			int amb = dicts.get(id);
			HashMap<String, Integer> hm = values.get(id);
			int div = 0;
			int squrecnt = 0;
			Iterator<String> iters = hm.keySet().iterator();
			while(iters.hasNext())
			{
				String value = iters.next();
				squrecnt += Math.pow(hm.get(value),2);
			}
			div = hm.keySet().size();
			//double entro = squrecnt*1.0/div;
			//double score = amb*1.0/entro;
			double score = div;
			if(topK.keySet().size()<topKsize && div >1)
			{
				topK.put(id, score);
			}
			else
			{
				String[] keys = topK.keySet().toArray(new String[topK.keySet().size()]);
				for(String key:keys)
				{
					if(topK.get(key)<score)
					{
						topK.remove(key);
						topK.put(id, score);
					}
				}
			}
		}
		return topK;
	}
	@Override
	public UpdateContainer undoIt(VWorkspace vWorkspace) {
		// TODO Auto-generated method stub
		return null;
	}
	public static void main(String[] args)
	{
		String dirpath = "/Users/bowu/Research/testdata/TestSingleFile";
		File nf = new File(dirpath);
		File[] allfiles = nf.listFiles();
		for(File f:allfiles)
		{
			try
			{
				if(f.getName().indexOf(".csv")==(f.getName().length()-4))
				{
					
					CSVReader cr = new CSVReader(new FileReader(f),'\t');
					String[] pair;
					int isadded = 0;
					HashMap<String,String> tx = new HashMap<String,String>();
					int i = 0;
					Vector<TransformationExample> vrt = new Vector<TransformationExample>();
					while ((pair=cr.readNext())!=null)
					{
						
						pair[0] = "<_START>"+pair[0]+"<_END>";
						tx.put(i+"", pair[0]);
						if(isadded<2)
						{
							RamblerTransformationExample tmp = new RamblerTransformationExample(pair[0], pair[1], i+"");
							vrt.add(tmp);
							isadded ++;
						}
						i++;
					}				
					RamblerValueCollection vc = new RamblerValueCollection(tx);
					RamblerTransformationInputs inputs = new RamblerTransformationInputs(vrt, vc);
					//generate the program
					RamblerTransformationOutput rtf = new RamblerTransformationOutput(inputs);
					HashMap<String,Vector<String>> js2tps = new HashMap<String,Vector<String>>();
					Iterator<String> iter = rtf.getTransformations().keySet().iterator();
					Vector<ValueCollection> vvc = new Vector<ValueCollection>();
					while(iter.hasNext())
					{
						String tpid = iter.next();
						ValueCollection rvco = rtf.getTransformedValues(tpid);
						vvc.add(rvco);
						String reps = rvco.getJson().toString();
						if(js2tps.containsKey(reps))
						{
							js2tps.get(reps).add(tpid); // update the variance dic
						}
						else
						{
							Vector<String> tps = new Vector<String>();
							tps.add(tpid);
							js2tps.put(reps, tps);
						}
					}
					////////
					if(js2tps.keySet().size() == 0)
					{
						System.out.println("No Rules have been found");
						return; 
					}
					for(String s:js2tps.keySet())
					{
						System.out.println(""+s);
					}
				}			
			}
			catch(Exception ex)
			{
				System.out.println(""+ex.toString());
			}	
		}
	}
}
