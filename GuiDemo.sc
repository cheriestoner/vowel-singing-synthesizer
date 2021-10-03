/*
TODO:
1. Origanize the gui script with classes (x)
  1.1 layout stretch
2. Synth chain: read the previous one in the bus, add filter, and out
  2.1 SynthDefs are predefined so that we can call one anytime to create a Synth.
  2.2 The chain consists of Synths and should be created in the right order.
  2.3 Use Array to save and control different Synths separately.
3. MIDI control: Korg MicroKontrol, map knobs

Issues:
1. "SynthDef not found" when run Gui_main.new for the first time. But it works if we close the window and run it again.    Is it because synth is created before synthdef is compiled (x)
2. stretching is working weird
*/

Gui_main{
	var modules, src, filter;

	// this is a normal constructor method
	*new { // * indicates this is a class method
		/* | arga, argb, argc | */
        ^super.new.init(/*arga, argb, argc*/)
    }

    init {
		/* | arga, argb, argc | */
		modules = [
			["Audio Source", "Vocal Tract Formants"],
			[Gui_source, Gui_filter]
		];
		Server.default.waitForBoot({
			postln("Server booted.");
			this.compileSynthDefs;
			Server.default.sync;
			this.createPanel;
		});
    }

	compileSynthDefs{
		modules[1].do( {|class| if (class.notNil, { class.compileSynthDefs } )} )
	}

	createPanel{
		var window, srcView, filterView;

		window = Window("Gui Demo", Rect(
			Window.screenBounds.width/2-100,
			Window.screenBounds.height/2-100,
			600,
			500), scroll: true)
		.front
		.alwaysOnTop_(true);
		window.onClose({
			// todo: free the synth or stop sounds if window is closed
		});

		src = Gui_source.new;
		srcView = src.view;

		filter = Gui_filter.new;
		filterView = filter.view;

		window.layout = VLayout( [HLayout([srcView], [nil])], [filterView], [nil], [nil] );
		window.layout.margins_(1);
	}

}

Gui_source{
	/*
	Todo:
	1. Improve volume control scaling to log or exp. (X)
	  1.1 Use Server.volume? Changes the internal volume setting. May not be good
	2. Enable vibrato parameters
	*/
	var <view;

	*new { arg main;
		^super.new.init(main)
	}

	init { arg main;
		this.createPanel;
	}

	*compileSynthDefs {
		SynthDef(\Voxsource, {
			arg outBus=0,
			note=45, mul=1.0, fv=6.5;
			var source;
			source = ApexSource01.ar(SinOsc.kr(6.5, 0, 0.01, 110), mult:mul);
			// source = Blip.ar(SinOsc.kr(fv, 0, 0.01, note.midicps), 200, mul:mul);
			Out.ar(outBus, source);
			};
		).send(Server.default);
	}

	createPanel {
		var synth, title, button, noteBox, noteLabel, volSlider, volLabel, text;

		synth = Synth.new(\Voxsource);
		view = View()
		.background_(Color.gray.alpha_(0.3))
		.minSize_(Size(150, 80));
		// .resize_(5);
		// synth.inspect;

		// Source gui
		title = StaticText(view, 80@30).string_("Source sound");
		text = TextField(view, 80@60).string_("Here: vibrato and stuff.");

		button = Button(view,50@30)
		.states_([
			["Mute", Color.black, Color.gray(0.8)],  // value=0
			["Unmute", Color.white, Color(0.4, 1.0, 0.6)]    // value=1
		]);
		button.action_({
			arg obj;
			if(
				obj.value == 1,
				{synth.set(\mul, 0)},
				{synth.set(\mul, 1)}
			);
		});

		noteLabel = StaticText(view, 60@30).string_("MIDI Note");
		noteBox = NumberBox(view, 60@30)   // used to be an EZNumber which is more handy to program,
		.clipLo_(21).clipHi_(108).step_(1);// but crashes sometimes
		noteBox.action_({
			arg obj;
			var f;
			f = obj.value;
			synth.set(\note, f);
		})
		.valueAction_(45);

		volLabel = StaticText(view, 20@20).string_("Vol");
		volSlider = Slider(view, 20@60).value_(0.1);
		volSlider.action_({
			arg obj;
			var m;
			m = (obj.value.linexp(0, 1, 0.1, 20));
			synth.set(\mul, m);
			if( (obj.value > 0) && (button.value == 1), { button.value_(0); });
		});

		// Layout
		view.layout = HLayout(
			VLayout(
				HLayout([title], [button], [nil]),
				HLayout([noteLabel], [noteBox], [nil]),
				text,
				nil
			),
			VLayout([volLabel], [volSlider], [nil]),
			nil
		);
	}

}

Gui_filter{
	/*
	Todo:
	1. gui: add/remove filter panels
	2. implement formant chain here
	*/
	var <view, formantViews, synths, freqs, freqLos, freqHis, qs;

	*new { arg main;
		^super.new.init(main)
	}

	init { arg main;
		this.createPanel;
	}

	*compileSynthDefs{
		SynthDef(\Voxformant, {
			arg inBus=0, outBus=0,
			freq = 500, // default freq of F1
			res=0.1, low=0.1, band=0.0, high=0.0, notch=0.0, peak=0.0; //levels
			var signal;

			signal = In.ar(inBus, 1);  // intend to get the signal from inBus
			signal = SVF.ar(signal,    // add a filter
				freq, // cutoff freq
				res, // q^-1
				low, band, high, notch, peak, mul: 1);
			ReplaceOut.ar(outBus, signal);
		};
		).send(Server.default);
	}

	createPanel{
		var reLayoutAdd, reLayoutRemove, newButton, removeButton,
		levelView, lowLabel, notchLabel, lowKnob, notchKnob,
		reView, numFormants;

		reLayoutAdd = {
			// renew the synth once a new formant is enabled
			formantViews = [];
			numFormants.do({|i|
				this.createSubPanel(i);
			});
			reView.removeAll;
			reView.layout = HLayout(*formantViews); // an array of sub-views
			reView.refresh;
			// ("test ReLayoutAdd function. num formants"+numFormants+"\n").postln;
		};

		reLayoutRemove = {
			// renew the synth once a formant is removed
			formantViews = [];
			numFormants.do({|i|
				this.createSubPanel(i);
			});
			reView.removeAll;
			reView.layout = HLayout(*formantViews); // an array of sub-views
			reView.refresh;
			// "test ReLayoutRemove function\n".postln;
		};

		// Initials
		numFormants = 0;
		synths = [];
		view = View().minSize_(Size(400, 200));
		reView = View();
		freqs = [500, 1000, 2400, 4000];
		freqLos = [150, 300];
		freqHis = [1000, 2400];
		qs = Array.fill(5, 0.1);

		// Buttons
		newButton = Button(view, 20@20) // add a new formant
		.states_( [ ["+", Color.red, Color.gray(0.8)] ] );
		newButton.mouseDownAction_({
			var synth, freq, res; // create synth
			if( freqs[numFormants] == nil,
				{freq = 5000},
				{freq = freqs[numFormants]}
			);
			// create a new formant and add the synth into the chain
			synth = Synth(\Voxformant, [\freq, freq, \res, res], addAction: \addToTail);
			synths = synths.add(synth);
			numFormants = numFormants + 1;
			reLayoutAdd.();
		});
		removeButton = Button(view, 20@20) // remove a formant
		.states_( [ ["-", Color.black, Color.gray(0.8)] ] );
		removeButton.mouseDownAction_({
			if(	numFormants > 0, {
				numFormants = numFormants - 1;
				synths[numFormants].free; // free synth node
				synths.removeAt(numFormants); // remove synths[numFormants]
				reLayoutRemove.();
			}
			)
		});
		// Levels
		lowLabel = StaticText(levelView, 20@20).string_("Low");
		notchLabel = StaticText(levelView, 20@20).string_("Notch");
		lowKnob = Knob(levelView, 20@20);
		notchKnob = Knob(levelView, 20@20);
		lowKnob.action_({
			arg obj;
			var lv;
			lv = obj.value;
			synths.do( {|s, i| s.set(\low, lv)} )
		});
		notchKnob.action_({
			arg obj;
			var lv;
			lv = obj.value;
			synths.do( {|s, i| s.set(\notch, lv)} )
		});

		// Layout
		levelView = View(view);
		levelView.layout = HLayout(
			[VLayout( [lowLabel], [lowKnob], [nil] )],
			[VLayout( [notchLabel], [notchKnob], [nil] )],
			[nil]
		);
		view.layout = VLayout(
			[HLayout(
				[reView],
				[VLayout( [newButton], [removeButton], [nil] )],
				[nil]
			)],
			[levelView],
			[nil]
		);
	}

	createSubPanel{ arg i;/*, rangeLo, rangeHi;*/
		var subView, synth,
		fLabel, fUnit, freqBox, qLabel, qBox;

		subView = View()
		.minSize_(Size(40, 60))
		.background_(Color(0.5, 0.5, 1)); // blueish

		// Gui components
		fLabel = StaticText(subView, 40@30).string_("Formant " + (i+1)); // the i-th formant
		fUnit = StaticText(subView, 20@30).string_("Hz");
		freqBox = NumberBox(subView, 40@30).step_(0.1).decimals_(1);
		if(freqs[i] != nil, {freqBox.value_(freqs[i])});
		qLabel = StaticText(subView, 40@30).string_("Q factor");
		qBox = NumberBox(subView, 40@30).value_(10).clipLo_(1).step_(1);

		// Gui actions
		freqBox.action_({
			arg obj;
			var f;
			f = obj.value;
			if( freqs[i] == nil,
				{freqs = freqs.add(f)},
				{freqs[i] = f}
			);
			synths[i].set(\freq, f);
			postf("changing f% freq\n", i+1);
		});
		qBox.action_({
			arg obj;
			var q;
			q = (1/obj.value).postln;
			qs[i] = q;
			synths[i].set(\res, q);
			postf("changing f% q factor\n", i+1);
		});

		// Layout
		subView.layout = VLayout(
			[fLabel],
			[HLayout( [freqBox], [fUnit], [nil] )],
			[qLabel],
			[qBox],
			[nil]
		);

		formantViews = formantViews.add(subView);
	}
}