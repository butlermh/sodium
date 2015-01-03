package sodium

/**
 * An event that never fires.
 */
 class Stream[A] {
	import Stream._

	protected var listeners: List[TransactionHandler[A]] = List()
	protected var finalizers: List[Listener] = List()
	var node = new Node(0L)
	protected var firings: List[A] = List()

	protected def sampleNow(): IndexedSeq[A] = IndexedSeq()

	/**
	 * Listen for firings of this event. The returned Listener has an unlisten()
	 * method to cause the listener to be removed. This is the observer pattern.
     */
	 final def listen(action : Handler[A]): Listener = {
		return listen_(Node.NullNode, new TransactionHandler[A]() {
			 def run(trans2: Transaction, a: A) {
				action.run(a)
			}
		})
	}

	final def listen_(target: Node, action: TransactionHandler[A]): Listener =
		Transaction.apply(trans1 => listen(target, trans1, action, false))

	def listen(target: Node, 
			trans: Transaction, 
			action: TransactionHandler[A], 
			suppressEarlierFirings: Boolean): Listener = {
		 Transaction.listenersLock.synchronized {
            if (node.linkTo(target))
                trans.toRegen = true
            // TODO this is inefficient ..
            listeners = listeners ++ List(action)
        }
        trans.prioritized(target, new Handler[Transaction]() {
             def run(trans2: Transaction) {
                val aNow = sampleNow()
                aNow.foreach(a => action.run(trans, a))
                if (!suppressEarlierFirings) {
                    // Anything sent already in this transaction must be sent now so that
                    // there's no order dependency between send and listen.
                    firings.foreach(b => action.run(trans, b))
                }
            }
        })
		return new ListenerImplementation[A](this, action, target)
	}

    /**
     * Transform the event's value according to the supplied function.
     */
	 final def map[B](f: A => B): Stream[B] = {
	    val ev = this
	    val out = new StreamSink[B]() {
			protected override def sampleNow(): IndexedSeq[A] =
            {
                val oi = ev.sampleNow()
                if (oi != null) {
                    oi.map (x => f.apply(x))
                }
                else null
            }
	    }
        val l = listen_(out.node, new TransactionHandler[A]() {
        	 def run(trans2: Transaction, a: A) {
	            out.send(trans2, f.apply(a))
	        }
        })
        out.addCleanup(l)
	}

	/**
	 * Create a behavior with the specified initial value, that gets updated
     * by the values coming through the event. The 'current value' of the behavior
     * is notionally the value as it was 'at the start of the transaction'.
     * That is, state updates caused by event firings get processed at the end of
     * the transaction.
     */
	 final def hold(initValue: A): Cell[A] = 
		Transaction.apply(trans => new Cell[A](lastFiringOnly(trans), initValue))

	final def holdLazy(initValue: () => A): Cell[A] = 
		Transaction.apply(trans => new LazyCell[A](lastFiringOnly(trans), initValue))

	/**
	 * Variant of snapshot that throws away the event's value and captures the behavior's.
	 */
	 final def snapshot[B, C](beh: Cell[B]): Stream[B] =
	    snapshot[B, C](beh, (a: B, b: C) => b)

	/**
	 * Sample the behavior at the time of the event firing. Note that the 'current value'
     * of the behavior that's sampled is the value as at the start of the transaction
     * before any state changes of the current transaction are applied through 'hold's.
     */
	 final def snapshot[B,C](b: Cell[B], f: (A, B) => C): Stream[C] =
	{
	    val ev = this
		val out = new StreamSink[C]() {
            protected override def sampleNow(): IndexedSeq[A] = {
                val oi = ev.sampleNow()
                oi.map(x=> f.apply(x, b.sampleNoTrans()))
            }
		}
        val l = listen_(out.node, new TransactionHandler[A]() {
        	 def run(trans2: Transaction, a: A) {
	            out.send(trans2, f.apply(a, b.sampleNoTrans()))
	        }
        })
        out.addCleanup(l)
	}

    /**
     * Merge two streams of events of the same type.
     *
     * In the case where two event occurrences are simultaneous (i.e. both
     * within the same transaction), both will be delivered in the same
     * transaction. If the event firings are ordered for some reason, then
     * their ordering is retained. In many common cases the ordering will
     * be undefined.
     */
	 def merge(eb: Stream[A]): Stream[A] =
			 Stream.merge[A](this, eb)

	/**
	 * Push each event occurrence onto a new transaction.
	 */
	 final def delay(): Stream[A] = 
	{
	    val out = new StreamSink[A]()
	    val l1 = listen_(out.node, new TransactionHandler[A]() {
	         def run(trans: Transaction, a: A) {
	            trans.post(new Runnable() {
                     def run() {
                        val trans = new Transaction()
                        try {
                            out.send(trans, a)
                        } finally {
                            trans.close()
                        }
                    }
	            })
	        }
	    })
	    out.addCleanup(l1)
	}

    /**
     * If there's more than one firing in a single transaction, combine them into
     * one using the specified combining function.
     *
     * If the event firings are ordered, then the first will appear at the left
     * input of the combining function. In most common cases it's best not to
     * make any assumptions about the ordering, and the combining function would
     * ideally be commutative.
     */
	 final def coalesce(f: (A,A) => A): Stream[A] =
	    Transaction.apply(trans => coalesce(trans, f))

	final def coalesce(trans1: Transaction, f: (A,A) => A): Stream[A] =
	{
	    val ev = this
	    val out = new StreamSink[A]() {
			
            protected override def sampleNow(): IndexedSeq[A] = {
                val oi = ev.sampleNow()
                if (oi != null) {
					A o = (A)oi[0]
                    for (int i = 1 i < oi.length i++)
                        o = f.apply(o, (A)oi[i])
                    return new Object[] { o }
                }
                else
                    return null
            }
	    }
        val h = new CoalesceHandler[A](f, out)
        val l = listen(out.node, trans1, h, false)
        out.addCleanup(l)
    }

    /**
     * Clean up the output by discarding any firing other than the last one. 
     */
    final def lastFiringOnly(trans: Transaction): Stream[A] =
        coalesce(trans, (first, second) => second)

    /**
     * Merge two streams of events of the same type, combining simultaneous
     * event occurrences.
     *
     * In the case where multiple event occurrences are simultaneous (i.e. all
     * within the same transaction), they are combined using the same logic as
     * 'coalesce'.
     */
     def merge(eb: Stream[A], f: (A,A) => A): Stream[A] =
        merge(eb).coalesce(f)

    /**
     * Only keep event occurrences for which the predicate returns true.
     */
     def filter(f: A => Boolean): Stream[A] =
    {
        val ev = this
        val out = new StreamSink[A]() {
            protected def sampleNow(): IndexedSeq[A] =
            {
                val oi = ev.sampleNow()
                if (oi != null) {
                    val oo = new Array[Object](oi.length)
                    var j = 0
                    for (i <- 0:oi.size)
                        if (f.apply(oi(i)))
                            oo(j++) = oi(i)
                    if (j == 0)
                        oo = null
                    else
                    if (j < oo.length) {
                        Object[] oo2 = new Object[j]
                        for (int i = 0 i < j i++)
                            oo2[i] = oo[i]
                        oo = oo2
                    }
                    return oo
                }
                else
                    return null
            }
        }
        val l = listen_(out.node, new TransactionHandler[A]() {
        	 def run(trans2: Transaction, a: A) {
	            if (f.apply(a)) out.send(trans2, a)
	        }
        })
        out.addCleanup(l)
    }

    /**
     * Filter out any event occurrences whose value is a Java null pointer.
     */
     final def filterNotNull(): Stream[A] =
        filter(a => a != null)

    /**
     * Let event occurrences through only when the behavior's value is True.
     * Note that the behavior's value is as it was at the start of the transaction,
     * that is, no state changes from the current transaction are taken into account.
     */
     final def gate(bPred: Cell[Boolean]): Stream[A] =
        snapshot(bPred, (a, pred) => if (pred) a else null).filterNotNull()

    /**
     * Transform an event with a generalized state loop (a mealy machine). The function
     * is passed the input and the old state and returns the new state and output value.
     */
     final def collect[B,S](initState: S, f: (A, S) => (B, S)): Stream[B] =
        Transaction.run[Stream[B]](() => {
            val ea = this
            val es = new StreamLoop[S]()
            val s = es.hold(initState)
            val ebs = ea.snapshot(s, f)
            val eb = ebs.map(bs => bs._1)
            val es_out = ebs.map(bs => bs._2)
            es.loop(es_out)
            eb
        })

    /**
     * Accumulate on input event, outputting the new state each time.
     */
     final def accum[S](initState: S , f: (A, S) => S): Cell[S] = 
        Transaction.run[Cell[S]](() => {
            val ea = this
            val es = new StreamLoop[S]()
            val s = es.hold(initState)
            val es_out = ea.snapshot(s, f)
            es.loop(es_out)
            es_out.hold(initState)
        })

    /**
     * Throw away all event occurrences except for the first one.
     */
     final def once(): Stream[A] = {
        // This is a bit long-winded but it's efficient because it deregisters
        // the listener.
        val ev = this
        val la = new Array[Listener](1)
        val out = new StreamSink[A]() {
            protected override def sampleNow(): IndexedSeq[A] = {
                val oi = ev.sampleNow()
                val oo = oi
                if (oo != null) {
                    if (oo.length > 1)
                        oo = new Object[] { oi[0] }
                    if (la[0] != null) {
                        la[0].unlisten()
                        la[0] = null
                    }
                }
                oo
            }
        }
        la[0] = ev.listen_(out.node, new TransactionHandler[A]() {
        	 def run(trans: Transaction, a: A) {
	            out.send(trans, a)
	            if (la[0] != null) {
	                la[0].unlisten()
	                la[0] = null
	            }
	        }
        })
        out.addCleanup(la[0])
    }

    def addCleanup(cleanup: Listener): Stream[A] = {
        finalizers.add(cleanup)
        this
    }

	@Override
	protected def finalize() {
    	 finalizers.foreach(_.unlisten)
	}
}

object Stream {
	final class ListenerImplementation[A](
			val event: Stream[A],
			val action: TransactionHandler[A],
			val target: Node) extends Listener {

		/**
		 * It's essential that we keep the listener alive while the caller holds
		 * the Listener, so that the finalizer doesn't get triggered.
		 */

		 def unlisten() {
			 Transaction.listenersLock.synchronized {
                event.listeners.remove(action)
                event.node.unlinkTo(target)
            }
		}

		protected def finalize() {
			unlisten()
		}
	}
	
    /**
     * Merge two streams of events of the same type.
     *
     * In the case where two event occurrences are simultaneous (i.e. both
     * within the same transaction), both will be delivered in the same
     * transaction. If the event firings are ordered for some reason, then
     * their ordering is retained. In many common cases the ordering will
     * be undefined.
     */
	private def merge[A](ea: Stream[A], eb: Stream[A]): Stream[A] =
	{
	    val out = new StreamSink[A]() {
            protected override def sampleNow(): IndexedSeq[A] = 
            		ea.sampleNow() ++ eb.sampleNow()
	    }
        val h = new TransactionHandler[A]() {
        	 def run(trans: Transaction, a: A) {
	            out.send(trans, a)
	        }
        }
        val l1 = ea.listen_(out.node, h)
        val l2 = eb.listen_(out.node, new TransactionHandler[A]() {
        	 def run(trans1: Transaction, a: A) {
                trans1.prioritized(out.node, new Handler[Transaction]() {
                     def run(trans2: Transaction) {
                        out.send(trans2, a)
                    }
                })
	        }
        })
        out.addCleanup(l1).addCleanup(l2)
	}
	
    /**
     * Filter the empty values out, and strip the Optional wrapper from the present ones.
     */
     final def filterOptional(ev: Stream[Option[A]]): Stream[A] =
    {
        val out = new StreamSink[A]() {
            protected override def sampleNow(): IndexedSeq[A] = {
                val oi = ev.sampleNow()
                if (oi != null) {
                    Object[] oo = new Object[oi.length]
                    int j = 0
                    for (int i = 0 i < oi.length i++) {
                        Optional<A> oa = (Optional<A>)oi[i]
                        if (oa.isPresent())
                            oo[j++] = oa.get()
                    }
                    if (j == 0)
                        oo = null
                    else
                    if (j < oo.length) {
                        Object[] oo2 = new Object[j]
                        for (int i = 0 i < j i++)
                            oo2[i] = oo[i]
                        oo = oo2
                    }
                    return oo
                }
                else
                    return null
            }
        }
        val l = ev.listen_(out.node, new TransactionHandler[Option[A]]() {
        	 def run(trans2: Transaction, oa: Option[A]) {
	            oa.foreach(x => out.send(trans2, x))
	        }
        })
        out.addCleanup(l)
    }
	
}