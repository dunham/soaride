/*
 *Copyright (c) 2009, Soar Technology, Inc.
 *All rights reserved.
 *
 *Redistribution and use in source and binary forms, with or without modification,   *are permitted provided that the following conditions are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *  * Neither the name of Soar Technology, Inc. nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 *THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY  *EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED   *WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.   *IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,   *INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT   *NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR   *PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,    *WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)   *ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE    *POSSIBILITY OF SUCH *DAMAGE. 
 *
 * 
 */
package com.soartech.soar.ide.core.ast;

public final class Test {
	// Data Members
	private ConjunctiveTest d_conjunctiveTest;
	private SimpleTest d_simpleTest;
	
	// Constructors
	public Test(ConjunctiveTest conjunctiveTest) {
		d_conjunctiveTest = conjunctiveTest;
	}
	
	public Test(SimpleTest simpleTest) {
		d_simpleTest = simpleTest;
	}
	
	// Accessors
	public final boolean isConjunctiveTest() {
		return d_conjunctiveTest != null;
	}
	
	public final SimpleTest getSimpleTest() {
		if(isConjunctiveTest()) {
			throw new IllegalArgumentException("not a simple test");
		}
		else
			return d_simpleTest;
	}
	
	public final ConjunctiveTest getConjunctiveTest() {
		if(!isConjunctiveTest()) {
			throw new IllegalArgumentException("not a conjunctive test");
		}
		else
			return d_conjunctiveTest;
	}

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        return isConjunctiveTest() ? d_conjunctiveTest.toString() :
                                     d_simpleTest.toString();
    }
    
    
}